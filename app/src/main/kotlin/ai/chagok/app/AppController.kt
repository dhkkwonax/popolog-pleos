package ai.chagok.app

import ai.chagok.app.core.AdCatalog
import ai.chagok.app.core.AdCommand
import ai.chagok.app.core.AdModePolicy
import ai.chagok.app.core.AdVoiceBudget
import ai.chagok.app.core.DriverIdentifier
import ai.chagok.app.core.EnergyEvent
import ai.chagok.app.core.EnergyEventDetector
import ai.chagok.app.core.Integrity
import ai.chagok.app.core.Retention
import ai.chagok.app.core.Rfc3161
import ai.chagok.app.core.Taxpayer
import ai.chagok.app.core.TripClassifier
import ai.chagok.app.core.TripEnd
import ai.chagok.app.core.TripRecorder
import ai.chagok.app.core.TripStart
import ai.chagok.app.data.ChagokDb
import ai.chagok.app.data.DemoSeed
import ai.chagok.app.model.ClassifiedBy
import ai.chagok.app.model.Driver
import ai.chagok.app.model.DriverMethod
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.ExpenseSource
import ai.chagok.app.model.Place
import ai.chagok.app.model.Powertrain
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import ai.chagok.app.vehicle.GatewayFactory
import ai.chagok.app.vehicle.VehicleGateway
import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.chagok.app.nav.Arrival
import ai.chagok.app.nav.NaviBridge
import ai.chagok.app.nav.NaviFactory
import ai.chagok.app.nav.PlaceCandidate
import ai.chagok.app.voice.Assistant
import ai.chagok.app.voice.VoiceFactory
import ai.chagok.app.voice.VoiceIo
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 차량 신호 → 운행·비용 자동 기록의 중심.
 * 화면은 [addListener]로 변경 알림을 받아 다시 그린다.
 */
class AppController(private val context: Context) : VehicleGateway.Listener, TripRecorder.Listener {
    val db = ChagokDb(context)
    val admin = ai.chagok.app.data.AdminAuth(context)
    val canManage: Boolean get() = admin.unlocked && !isDriving && !dataSuspended
    var currentTripId: Long? = null
        private set
    fun canEditTrip(id: Long): Boolean = !isDriving && !dataSuspended && !isTripLocked(id) && (canManage || currentTripId == id)
    fun canEditExpense(id: Long): Boolean = !isDriving && !dataSuspended && !isExpenseLocked(id) && (canManage || askExpenseId == id)

    /** 관리자가 제출 완료를 확인한 묶음. 포함된 운행·비용은 수정·삭제할 수 없다 */
    var submissions: List<Retention.Submission> = emptyList()
        private set
    private var lockedTrips: Set<Long> = emptySet()
    private var lockedExpenses: Set<Long> = emptySet()
    fun isTripLocked(id: Long) = id in lockedTrips
    fun isExpenseLocked(id: Long) = id in lockedExpenses
    private val maintenanceLock = Any()
    @Volatile var dataSuspended = context.getSharedPreferences("record_backup", Context.MODE_PRIVATE).let { it.getString("sale", "").orEmpty().isNotEmpty() || it.getBoolean("restoring", false) }
        private set
    private var writeEpoch = 0

    fun pauseRecords() {
        admin.lock()
        currentTripId = null
        synchronized(maintenanceLock) { dataSuspended = true; writeEpoch++ }
        handler.removeCallbacksAndMessages(null)
        if (::gateway.isInitialized) gateway.stop()
        if (::navi.isInitialized) navi.stop()
        if (::voice.isInitialized) {
            assistant.clear()
            voice.shutdown()
        }
        stamping = false
    }

    fun resumeRecords() {
        dataSuspended = false
        driver = null; driverGreeting = null; askTripId = null; askExpenseId = null
        odoKm = 0.0; tickDay = null
        start()
        notifyChanged()
    }
    lateinit var gateway: VehicleGateway
        private set

    var odoKm = 0.0
        private set
    var energyPercent = 0.0
        private set
    var parkingBrake = true
        private set
    var lat = 0.0
        private set
    var lng = 0.0
        private set

    var places: List<Place> = emptyList()
        private set
    var trips: List<Trip> = emptyList()
        private set
    var expenses: List<Expense> = emptyList()
        private set

    /** 방금 끝난 미분류 운행 → 홈 화면 상단에서 목적을 묻는다 */
    var askTripId: Long? = null
        private set

    /** 방금 감지된 주유·충전 → 금액을 묻는다 */
    var askExpenseId: Long? = null
        private set

    /** 가장 최근 자동 기록 알림 (홈 화면 타임라인용) */
    var lastNotice: String = ""
        private set

    var drivers: List<Driver> = emptyList()
        private set

    /** 위변조 검증 결과. 기록이 바뀔 때마다 체인을 다시 계산한다 */
    var integrity: Integrity.Report = Integrity.Report(true, 0)
        private set
    var anchors: List<Integrity.DailyAnchor> = emptyList()
        private set

    /** 시연 '기록 위조해 보기' 로 바꾼 줄이 있으면 그 줄 번호 */
    val forgedSeq: Long? get() = db.setting(KEY_FORGED).substringBefore('\t').toLongOrNull()

    /** 지금 운전석에 앉은 사람과 알아낸 방법. 시동 직후 판별 전에는 null */
    var driver: DriverIdentifier.Result? = null
        private set

    /** 시동 직후 "OOO님으로 인식했어요" 안내. 잠시 뒤 사라진다 */
    var driverGreeting: String? = null
        private set

    /** 마지막으로 읽은 운전석 위치와, 판별할 때의 위치 */
    private var seatPosition: Int? = null
    private var seatAtIdentify: Int? = null

    private val listeners = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recorder: TripRecorder
    private lateinit var energy: EnergyEventDetector

    /** 말하기·듣기. 차량은 Pleos TTS·STT, 시연 모드는 안드로이드 TTS 와 화면 입력 */
    lateinit var voice: VoiceIo
        private set

    /** 내비(도착 이벤트·즐겨찾기). 시연 모드는 시뮬레이션 */
    lateinit var navi: NaviBridge
        private set

    /**
     * 음성 사용 선택. 데모는 항상 켜짐, 실제 모드는 처음 안내를 보고 고르기 전까지 꺼짐이다.
     * 끄면 앱은 말하지도 듣지도 않고 모든 질문을 화면 버튼·입력으로 받는다.
     */
    val voiceEnabled: Boolean get() = db.setting(KEY_VOICE, if (demo) "on" else "") == "on"

    val voiceUndecided: Boolean get() = !demo && db.setting(KEY_VOICE).isEmpty()

    private fun makeVoice(): VoiceIo =
        if (voiceEnabled) VoiceFactory.create(context) { typedInputHandler } else ai.chagok.app.voice.TextOnlyVoice { typedInputHandler }

    fun setVoiceEnabled(on: Boolean) {
        if (dataSuspended) return
        db.setSetting(KEY_VOICE, if (on) "on" else "off")
        if (::voice.isInitialized) {
            assistant.clear()
            voice.shutdown()
            voice = makeVoice()
        }
        notifyChanged()
    }

    /** 말한 내용을 화면에서 입력받는 창을 여는 훅 (MainActivity 가 정한다) */
    var typedInputHandler: ((String, (String?) -> Unit) -> Unit)? = null

    /** 앱이 먼저 묻는 질문 진행기. 정차 중에만 묻는다 */
    val assistant = Assistant({ voice }, { isStopped() }, { notifyChanged() }, { handleAdCommand(it) })
    val flows = Flows(this)

    /** 비용 내역을 가져오는 곳. 실제 경로가 확인되면 어댑터만 바꿔 끼운다. 시연 모드는 샘플, 실제 모드는 아직 없음 */
    val expenseSource: ai.chagok.app.core.ExpenseSource by lazy {
        if (demo) ai.chagok.app.core.SampleExpenseSource { gateway.now() } else ai.chagok.app.core.NoExpenseSource
    }
    private var lastNaviArrival = 0L

    private fun isStopped() = ::recorder.isInitialized && !recorder.isDriving

    // ---- 광고 모드 (운행 기록·해시체인·세무와 분리: 설정 표의 ad_ 키만 쓰고 record() 는 부르지 않는다) ----
    private val adPolicy = AdModePolicy(AdCatalog.all.size).also { it.setEnabled(false) }
    val adEnabled: Boolean get() = db.setting(KEY_AD) == "on"
    val adConsented: Boolean get() = db.setting(KEY_AD_CONSENT).isNotEmpty()

    /** 지금 전체 화면 광고를 보여 줄 때 */
    val adVisible: Boolean get() = !dataSuspended && adEnabled && adPolicy.screen == AdModePolicy.Screen.AD
    val currentAd get() = AdCatalog.all[adPolicy.creative]
    private var adShown = false
    private fun uptime() = android.os.SystemClock.elapsedRealtime()

    /** 켜기는 정차 중에만, 처음에는 [consent] 로 동의를 받은 뒤에만 된다 */
    fun setAdMode(on: Boolean, consent: Boolean = adConsented): Boolean {
        if (dataSuspended || adVisible || isDriving) return false
        if (on && !consent) return false
        if (on && !adConsented) db.setSetting(KEY_AD_CONSENT, gateway.now().toString())
        db.setSetting(KEY_AD, if (on) "on" else "off")
        adPolicy.setEnabled(on)
        adShown = false
        notifyChanged()
        return true
    }

    private fun syncAd() {
        val now = uptime()
        adPolicy.tick(now)
        val visible = adVisible
        if (visible != adShown) { adShown = visible; notifyChanged() }
    }

    /** 광고 화면의 "앱 화면으로" 버튼. 정차 여부와 상관없이 눌러서 돌아갈 수 있다 */
    fun dismissAd() {
        if (dataSuspended || !adPolicy.dismiss()) return
        syncAd()
    }

    /** 음성 "광고 그만". 해제 즉시 일반 화면 */
    fun handleAdCommand(said: String): Boolean {
        if (!AdCommand.isStop(said)) return false
        db.setSetting(KEY_AD, "off")
        adPolicy.setEnabled(false)
        adShown = false
        speak("광고 모드를 껐어요.")
        notifyChanged()
        return true
    }

    /** 정차 중이고 오늘 2회 미만일 때만 소리 광고를 한 번 묻는다 */
    private fun offerSoundAd() {
        if (dataSuspended || !adEnabled || adVisible || isDriving || !::voice.isInitialized) return
        val today = Integrity.dayOf(gateway.now())
        if (!AdVoiceBudget.parse(db.setting(KEY_AD_VOICE), today).canOffer) return
        db.setSetting(KEY_AD_VOICE, AdVoiceBudget.afterOffer(db.setting(KEY_AD_VOICE), today))
        voice.speak(currentAd.voiceText) {
            voice.listen(currentAd.voiceText) { said ->
                if (said == null) return@listen
                if (handleAdCommand(said)) return@listen
                lastNotice = if (said.contains("안내")) "광고 안내 요청 · 경로 연동은 아직 없음" else "광고 건너뜀"
                notifyChanged()
            }
        }
    }

    override fun onSpeed(kmh: Double?) {
        if (dataSuspended) return
        adPolicy.onSpeed(kmh, uptime())
        syncAd()
    }

    fun notifyUi() = notifyChanged()

    private var tickDay: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (dataSuspended) return
            val now = gateway.now()
            recorder.tick(now)
            energy.tick(now)
            syncAd()
            // 날짜가 바뀌면 지난 날을 마감한다
            val day = Integrity.dayOf(now)
            if (day != tickDay) {
                if (tickDay != null) anchorPastDays(now)
                tickDay = day
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    val isDriving: Boolean get() = !dataSuspended && ::recorder.isInitialized && recorder.isDriving
    val liveTripKm: Double get() = if (!::recorder.isInitialized || dataSuspended) 0.0 else recorder.currentTrip?.let { odoKm - it.odoKm } ?: 0.0

    val powertrain: Powertrain
        get() = if (db.setting(KEY_POWERTRAIN, Powertrain.ICE.name) == Powertrain.EV.name) Powertrain.EV else Powertrain.ICE

    /** 데모 모드(가상 차량·예시 데이터·시연 오버레이) 빌드인지. 실제 동작 모드는 Pleos SDK 값만 쓴다 */
    val demo: Boolean get() = BuildConfig.DEMO

    val taxpayer: Taxpayer get() = Taxpayer.of(db.setting(KEY_TAXPAYER))

    fun setTaxpayer(t: Taxpayer) {
        if (dataSuspended) return
        db.setSetting(KEY_TAXPAYER, t.key)
        notifyChanged()
    }

    /** 외부 타임스탬프 기관에 날짜·해시 지문을 보낼지. 설정에서 끌 수 있고, 꺼 두면 앵커는 응답 대기로 남는다 */
    val tsaEnabled: Boolean get() = db.setting(KEY_TSA, "on") != "off"

    fun setTsaEnabled(on: Boolean) {
        // 관리자 PIN 인증 뒤에만 바꾼다. 관리자를 설정하지 않은 차량은 기본 켜짐을 유지한다
        if (dataSuspended || !canManage) return
        db.setSetting(KEY_TSA, if (on) "on" else "off")
        if (on) stampPending() else notifyChanged()
    }

    /** 타임스탬프 요청 중인 앵커가 있는지 (화면 안내용) */
    @Volatile
    var stamping = false
        private set
    private val tsaExecutor = Executors.newSingleThreadExecutor()

    /** 연결 뒤 첫 계기판 값으로 앱이 꺼져 있던 동안의 거리를 확인한다 */
    private var checkGap = false

    fun start() {
        if (dataSuspended) {
            gateway = GatewayFactory.create(context, powertrain, db)
            voice = makeVoice()
            navi = NaviFactory.create(context)
            reload()
            return
        }
        if (demo && db.setting(KEY_SEEDED) != "1") {
            DemoSeed.seed(db, powertrain)
            db.setSetting(KEY_SEEDED, "1")
        }
        adPolicy.setEnabled(adEnabled)
        ensureLedger()
        // 앱이 꺼져 있던 동안 지난 날도 마감한다
        anchorPastDays(System.currentTimeMillis())
        reload()
        voice = makeVoice()
        navi = NaviFactory.create(context)
        navi.start(object : NaviBridge.Listener {
            override fun onArrived(a: Arrival) {
                lastNaviArrival = System.currentTimeMillis()
                handler.post { if (!dataSuspended) flows.onArrived(a) }
            }

            override fun onCandidates(list: List<PlaceCandidate>) {
                flows.candidates = list
            }
        })
        connectGateway()
        handler.post(ticker)
        stampPending()
        // 정비 시기가 된 항목이 있으면 시동 직후 정차 상태에서 한 번 알린다
        handler.postDelayed({ if (!dataSuspended) flows.checkMaintenance() }, MAINT_CHECK_DELAY_MS)
    }

    fun stop() {
        if (dataSuspended) { pauseRecords(); return }
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(identifyOnIgnition)
        handler.removeCallbacks(clearGreeting)
        if (::recorder.isInitialized) recorder.finish(gateway.now())
        // 다음 실행 때 꺼져 있던 동안의 거리를 잴 기준
        if (odoKm > 0) db.setSetting(KEY_LAST_ODO, odoKm.toString())
        if (demo) saveSimState()
        synchronized(maintenanceLock) { writeEpoch++ }
        gateway.stop()
        assistant.clear()
        navi.stop()
        voice.shutdown()
    }

    /** [fresh] 가 false 면 진행 중인 운행·주유 감지 상태를 이어 간다 (같은 차에 다시 붙을 때) */
    private fun connectGateway(fresh: Boolean = true) {
        if (dataSuspended) return
        if (fresh || !::recorder.isInitialized) {
            checkGap = true
            recorder = TripRecorder(this)
            energy = EnergyEventDetector(
                thresholdPercent = if (powertrain == Powertrain.EV) 3.0 else 5.0,
                onEvent = ::onEnergyEvent,
            )
        }
        gateway = GatewayFactory.create(context, powertrain, db)
        gateway.start(this)
        // 시동(앱·게이트웨이 연결) 직후, 시트·위치 신호가 들어올 틈을 두고 운전자를 판별한다
        handler.removeCallbacks(identifyOnIgnition)
        handler.postDelayed(identifyOnIgnition, IDENTIFY_DELAY_MS)
    }

    private val identifyOnIgnition = Runnable { identifyDriver() }
    private val clearGreeting = Runnable {
        driverGreeting = null
        notifyChanged()
    }

    /** 시트·출발 시각·출발 장소·마지막 운전자로 운전자를 정한다 */
    fun identifyDriver() {
        if (dataSuspended) return
        val origin = ai.chagok.app.core.Geo.nearestPlace(lat, lng, places)?.name.orEmpty()
        val r = DriverIdentifier.identify(drivers.filter(::hasConsent), seatPosition, gateway.now(), origin, trips, db.setting(KEY_LAST_DRIVER)) ?: return
        applyDriver(r)
    }

    /** 머리말이나 설정에서 직접 고를 때 */
    fun chooseDriver(id: String) {
        if (dataSuspended) return
        val d = drivers.firstOrNull { it.id == id } ?: return
        applyDriver(DriverIdentifier.Result(d, DriverMethod.MANUAL))
    }

    private fun applyDriver(r: DriverIdentifier.Result) {
        if (dataSuspended) return
        driver = r
        seatAtIdentify = seatPosition
        db.setSetting(KEY_LAST_DRIVER, r.driver.id)
        driverGreeting = if (r.method == DriverMethod.MANUAL) "${r.driver.name}님으로 바꿨어요" else "${r.driver.name}님으로 인식했어요"
        // 인식한 결과를 말하고 맞는지 묻는다 (틀리면 누구인지 다시 묻는다)
        flows.confirmDriver(r.driver.name, r.method)
        handler.removeCallbacks(clearGreeting)
        handler.postDelayed(clearGreeting, GREETING_MS)
        notifyChanged()
    }

    /** 위치 기록·운전자 추정에 동의한 사람인지 (데모 예시 운전자는 동의한 것으로 본다). 동의 없는 사람은 자동 추정 대상이 아니다 */
    fun hasConsent(d: Driver): Boolean = demo || d.consentAt > 0

    /** 본인이 동의를 확인했거나 철회했다 */
    fun setDriverConsent(id: String, consent: Boolean) {
        if (dataSuspended) return
        drivers = drivers.map { if (it.id == id) it.copy(consentAt = if (consent) gateway.now() else 0L, seatPosition = if (consent) it.seatPosition else null) else it }
        db.saveDrivers(drivers)
        if (!consent && driver?.driver?.id == id && driver?.method != DriverMethod.MANUAL) driver = null
        notifyChanged()
    }

    /** 운전자 등록 (실제 동작 모드). 처음 등록한 사람은 바로 지금 운전자로 둔다 */
    fun addDriver(name: String, department: String, consent: Boolean = false) {
        if (dataSuspended) return
        val d = Driver("d" + System.currentTimeMillis().toString(36), name, department, null, if (consent) gateway.now() else 0L)
        drivers = drivers + d
        db.saveDrivers(drivers)
        if (driver == null) chooseDriver(d.id) else notifyChanged()
    }

    /** 지금 운전석 위치를 이 사람의 위치로 저장 (다음 시동부터 시트로 알아본다) */
    fun saveSeatFor(id: String) {
        if (dataSuspended || drivers.none { it.id == id && hasConsent(it) }) return
        val pos = seatPosition ?: return
        drivers = drivers.map { if (it.id == id) it.copy(seatPosition = pos) else it }
        db.saveDrivers(drivers)
        notifyChanged()
    }

    /** 데모 설정에서 가솔린 ↔ 전기차 전환 */
    fun switchPowertrain(p: Powertrain) {
        if (dataSuspended) return
        if (p == powertrain) return
        stopGatewayOnly()
        db.setSetting(KEY_POWERTRAIN, p.name)
        db.setSetting(KEY_LAST_ODO, "")
        connectGateway()
        notifyChanged()
    }

    fun resetDemo() {
        if (!canManage) return
        if (!demo) return
        stopGatewayOnly()
        val p = powertrain
        db.clearAll()
        DemoSeed.seed(db, p)
        db.setSetting(KEY_POWERTRAIN, p.name)
        db.setSetting(KEY_SEEDED, "1")
        ensureLedger()
        stampPending()
        askTripId = null
        askExpenseId = null
        lastNotice = ""
        reload()
        connectGateway()
        notifyChanged()
    }

    private fun stopGatewayOnly() {
        gateway.stop()
    }

    /** 차량 권한이 뒤늦게 승인되면 다시 연결한다 (Pleos 빌드의 런타임 권한). 주행 중이어도 운행은 끊지 않는다 */
    fun reconnectGateway() {
        if (dataSuspended) return
        if (!::gateway.isInitialized) return
        stopGatewayOnly()
        connectGateway(fresh = false)
        notifyChanged()
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun notifyChanged() = listeners.toList().forEach { it() }

    fun reload() {
        drivers = db.drivers()
        places = db.places()
        trips = db.trips()
        expenses = db.expenses()
        anchors = db.anchors()
        submissions = db.submissions()
        lockedTrips = submissions.flatMapTo(HashSet()) { it.tripIds }
        lockedExpenses = submissions.flatMapTo(HashSet()) { it.expenseIds }
        integrity = Integrity.verify(db.ledger(), anchors, trips, expenses)
    }

    // ---- 위변조 검증 (해시체인) ----

    /**
     * 체인이 비어 있으면 지금 표로 처음 만든다 (v1 DB 마이그레이션·시연 데이터).
     * 오늘 이전 날짜는 앵커까지 만들어, 시연에서 '마지막 앵커'가 바로 보이게 한다.
     */
    private fun ensureLedger() {
        if (db.ledger().isNotEmpty()) return
        val chain = Integrity.build(db.trips(), db.expenses())
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            chain.forEach(db::appendLedger)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        anchorPastDays(System.currentTimeMillis(), chain, backfill = true)
    }

    /** 체인에 한 줄 덧붙인다. 표의 기록은 최신 값으로 바뀌어도 체인의 이전 줄은 그대로 남는다 */
    private fun record(kind: Integrity.Kind, ref: Long, payload: String) {
        db.appendLedger(Integrity.append(db.ledger(), gateway.now(), kind, ref, payload))
    }

    /**
     * 앵커가 없는 지난 날을 마감한다 (하루가 끝날 때). 시연 시계가 날짜를 넘겨도 여기서 잡는다.
     * [backfill] 이면(기존 기록을 처음 옮길 때) 마지막 날만 기관 서명을 받고 그 앞날은 이관 앵커로 둔다
     */
    private fun anchorPastDays(now: Long, chain: List<Integrity.Entry> = db.ledger(), backfill: Boolean = false) {
        val today = Integrity.dayOf(now)
        val done = db.anchors().map { it.day }.toSet()
        val entryDays = chain.map { Integrity.dayOf(it.time) }
        val days = entryDays.distinct().filter { it < today && it !in done }
        if (days.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (d in days) {
                // 그날 끝(23:59) 직후 기관 영수증을 받은 것으로 둔다
                val stampAt = endOfDay(d) + 5 * 60_000L
                val receipt = if (backfill && d != days.last()) Rfc3161.BACKFILL else ""
                Integrity.anchorFor(chain, entryDays, d, stampAt, receipt)?.let(db::saveAnchor)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        if (::gateway.isInitialized) {
            reload()
            stampPending()
        }
    }

    /**
     * 기관 응답 전인 앵커마다 RFC 3161 타임스탬프를 받는다 (백그라운드). 오프라인이면 다음 기회(앱 시작·하루 마감)에 다시 한다.
     * 기관에는 날짜와 hash 의 지문만 간다.
     */
    fun stampPending(onDone: (() -> Unit)? = null) {
        if (dataSuspended || !tsaEnabled) return
        val pending = db.anchors().filter { it.receipt.isEmpty() }
        if (pending.isEmpty() || stamping) return
        stamping = true
        notifyChanged()
        val epoch = writeEpoch
        tsaExecutor.execute {
            var ok = 0
            for (a in pending) {
                val receipt = Rfc3161.stamp(a.hash, a.day) ?: continue
                val t = Rfc3161.check(receipt, a.hash, a.day)?.time ?: a.stampedAt
                synchronized(maintenanceLock) {
                    if (dataSuspended || epoch != writeEpoch) return@execute
                    db.saveAnchor(a.copy(receipt = receipt, stampedAt = t))
                }
                ok++
            }
            if (demo) android.util.Log.i("Popolog", "tsa stamped $ok/${pending.size}")
            handler.post {
                if (dataSuspended || epoch != writeEpoch) return@post
                stamping = false
                reload()
                notifyChanged()
                onDone?.invoke()
            }
        }
    }

    private fun endOfDay(day: String): Long = java.util.Calendar.getInstance(ai.chagok.app.core.TaxReport.KST).apply {
        val (y, m, d) = day.split('-').map { it.toInt() }
        clear(); set(y, m - 1, d, 23, 59, 0)
    }.timeInMillis

    /** '오늘 기록 앵커': 오늘까지의 마지막 hash 를 앵커로 묶고 기관(RFC 3161) 타임스탬프를 요청한다. 결과는 [onStamped] */
    fun closeDay(onStamped: ((Integrity.DailyAnchor?) -> Unit)? = null): Integrity.DailyAnchor? {
        if (dataSuspended) return null
        val now = gateway.now()
        val a = Integrity.anchorFor(db.ledger(), Integrity.dayOf(now), now) ?: return null
        db.saveAnchor(a)
        reload()
        lastNotice = "오늘 기록 ${a.count}건 앵커 · ${Integrity.verifyCode(a.hash)}"
        notifyChanged()
        stampPending { onStamped?.invoke(db.anchors().firstOrNull { it.day == a.day }) }
        return a
    }

    /**
     * 시연 '기록 위조해 보기': 지난달 운행 하나의 도착 계기판 값을 30km 늘린다.
     * 앱 화면을 거치지 않고 DB 를 직접 고친 것처럼, 운행 표와 체인 줄의 내용을 함께 바꾼다 (hash 는 그대로).
     */
    fun forgeRecord() {
        if (!canManage) return
        if (forgedSeq != null) return
        val chain = db.ledger()
        val target = chain.filter { it.kind == Integrity.Kind.TRIP }.let { it.getOrNull(it.size - 25) ?: it.firstOrNull() } ?: return
        val t = trips.firstOrNull { it.id == target.ref } ?: return
        val forged = t.copy(endOdoKm = t.endOdoKm + 30.0)
        db.setSetting(KEY_FORGED, "${target.seq}\t${target.payload}\t${t.endOdoKm}")
        db.forgeLedgerPayload(target.seq, Integrity.canonical(forged))
        db.upsert(forged)
        reload()
        notifyChanged()
    }

    /** 위조를 되돌린다 */
    fun undoForge() {
        if (!canManage) return
        val f = db.setting(KEY_FORGED).split('\t')
        if (f.size < 3) return
        val seq = f[0].toLong()
        val ref = db.ledger().firstOrNull { it.seq == seq }?.ref
        db.forgeLedgerPayload(seq, f[1])
        trips.firstOrNull { it.id == ref }?.let { db.upsert(it.copy(endOdoKm = f[2].toDouble())) }
        db.setSetting(KEY_FORGED, "")
        reload()
        notifyChanged()
    }

    fun speak(text: String) {
        if (::voice.isInitialized) voice.speak(text)
    }

    // ---- VehicleGateway.Listener ----
    override fun onOdometer(km: Double) {
        if (dataSuspended) return
        odoKm = km
        if (checkGap) {
            checkGap = false
            recordOfflineGap(km)
        }
        recorder.onOdometer(km, gateway.now())
        notifyChanged()
    }

    override fun onEnergyLevel(percent: Double) {
        if (dataSuspended) return
        energyPercent = percent
        energy.onLevel(percent, gateway.now(), recorder.isDriving)
        notifyChanged()
    }

    override fun onParkingBrake(engaged: Boolean) {
        if (dataSuspended) return
        parkingBrake = engaged
        recorder.onParkingBrake(engaged, gateway.now())
        if (engaged) {
            assistant.pump()
            flows.checkMaintenance()
        }
        notifyChanged()
    }

    override fun onLocation(lat: Double, lng: Double) {
        if (dataSuspended) return
        this.lat = lat
        this.lng = lng
        recorder.onLocation(lat, lng)
    }

    override fun onDriverSeat(position: Int) {
        if (dataSuspended) return
        seatPosition = position
        // 주차 중에 운전석이 크게 움직였으면 다른 사람이 탄 것으로 보고 다시 판별한다 (시동 전 시트 조정)
        val before = seatAtIdentify
        if (driver != null && !recorder.isDriving && (before == null || kotlin.math.abs(before - position) > DriverIdentifier.SEAT_TOLERANCE)) {
            identifyDriver()
        }
    }

    /**
     * 앱이 꺼져 있던 동안 달린 거리. 마지막으로 본 계기판 값보다 늘었으면 그 차이를 한 건의 운행으로 남기고 목적을 묻는다.
     * 출발 시각·장소는 알 수 없어 마지막 기록 시각과 지금 위치로 두고, 메모에 보정 기록임을 적는다.
     */
    private fun recordOfflineGap(km: Double) {
        val last = db.setting(KEY_LAST_ODO).toDoubleOrNull()
        if (last == null || km - last < GAP_MIN_KM || recorder.isDriving) {
            if (last == null || km > last) db.setSetting(KEY_LAST_ODO, km.toString())
            return
        }
        val now = gateway.now()
        val prev = trips.maxByOrNull { it.endTime }
        val from = prev?.let { it.endLat to it.endLng } ?: (lat to lng)
        val c = TripClassifier.classify(from.first, from.second, lat, lng, places)
        val trip = Trip(
            startTime = prev?.endTime?.coerceAtMost(now) ?: now, endTime = now,
            startOdoKm = last, endOdoKm = km,
            startLat = from.first, startLng = from.second, endLat = lat, endLng = lng,
            purpose = TripPurpose.UNCLASSIFIED, classifiedBy = ClassifiedBy.NONE,
            fromName = c.fromPlace?.name ?: "", toName = c.toPlace?.name ?: "",
            memo = GAP_MEMO, driver = driver?.driver?.name ?: db.setting(KEY_DRIVER),
        )
        val id = db.upsert(trip)
        currentTripId = id
        record(Integrity.Kind.TRIP, id, Integrity.canonical(trip.copy(id = id)))
        db.setSetting(KEY_LAST_ODO, km.toString())
        reload()
        askTripId = id
        lastNotice = "앱이 꺼진 동안 ${fmtKm(trip.distanceKm)} 운행 · 계기판으로 보정"
        if (demo) android.util.Log.i("Popolog", "offline gap")
        speak("앱이 꺼져 있던 동안 ${fmtKmSpoken(trip.distanceKm)}를 달렸어요. 업무 운행이었나요?")
        notifyChanged()
    }

    /** 데모: 앱을 끈 채 [km] 만큼(시속 40km 로) 달린 것처럼 가상 차량을 다시 붙인다 (계기판만 늘어난 채로 다시 시동) */
    fun demoOfflineDrive(km: Double) {
        if (dataSuspended) return
        if (!demo) return
        if (odoKm > 0) db.setSetting(KEY_LAST_ODO, odoKm.toString())
        saveSimState(extraKm = km, extraMs = (km / 40.0 * 3_600_000L).toLong())
        stopGatewayOnly()
        connectGateway()
        notifyChanged()
    }

    /**
     * 데모: 시동을 끄고 다시 켠다. 주행 중이면 운행이 그 자리에서 끝나고, 다시 켜면 운전자를 새로 판별한다.
     * 다른 사람이 타려면 끈 뒤 '탑승' 으로 시트를 맞춘다.
     */
    fun demoRestartEngine() {
        if (dataSuspended) return
        if (!demo) return
        if (::recorder.isInitialized) recorder.finish(gateway.now())
        if (odoKm > 0) db.setSetting(KEY_LAST_ODO, odoKm.toString())
        saveSimState(extraMs = 60_000L)
        stopGatewayOnly()
        driver = null
        lastNotice = "시동 끔 → 다시 켬"
        connectGateway()
        notifyChanged()
    }

    /** 다음 가상 차량이 이어받을 계기판·위치·연료와 가상 시각 */
    private fun saveSimState(extraKm: Double = 0.0, extraMs: Long = 0L) {
        db.setSetting(KEY_SIM_STATE, listOf(odoKm + extraKm, lat, lng, energyPercent).joinToString(","))
        db.setSetting(KEY_SIM_CLOCK, (gateway.now() + extraMs).toString())
    }

    /** 시뮬레이터에서 주유·충전이 끝났음을 알릴 때 */
    fun flushEnergy() { if (!dataSuspended && ::energy.isInitialized) energy.flush(gateway.now()) }

    // ---- TripRecorder.Listener ----
    override fun onTripStarted(start: TripStart) {
        if (dataSuspended) return
        // 시연 자동 촬영이 logcat 으로 운행 시작·끝을 확인한다 (운행 중에는 화면 읽기가 느려 쓸 수 없다)
        admin.lock()
        currentTripId = null
        if (demo) android.util.Log.i("Popolog", "trip started")
        lastNotice = "운행 시작 · 계기판 ${fmtKm(start.odoKm)}"
        // 새 운행이 시작되면 이전 운행의 질문을 다음 운전자에게 노출하지 않는다
        assistant.clear()
        askTripId = null
        notifyChanged()
    }

    override fun onTripEnded(end: TripEnd) {
        if (dataSuspended) return
        if (demo) android.util.Log.i("Popolog", "trip ended")
        val c = TripClassifier.classify(end.start.lat, end.start.lng, end.lat, end.lng, places)
        val trip = Trip(
            startTime = end.start.time, endTime = end.time,
            startOdoKm = end.start.odoKm, endOdoKm = end.odoKm,
            startLat = end.start.lat, startLng = end.start.lng, endLat = end.lat, endLng = end.lng,
            purpose = c.purpose,
            classifiedBy = if (c.purpose == TripPurpose.UNCLASSIFIED) ClassifiedBy.NONE else ClassifiedBy.PLACE,
            fromName = c.fromPlace?.name ?: "", toName = c.toPlace?.name ?: "",
            memo = if (c.purpose == TripPurpose.UNCLASSIFIED) "" else c.reason,
            driver = driver?.driver?.name ?: db.setting(KEY_DRIVER),
        )
        if (trip.distanceKm < 0.05) return
        val id = db.upsert(trip)
        currentTripId = id
        record(Integrity.Kind.TRIP, id, Integrity.canonical(trip.copy(id = id)))
        db.setSetting(KEY_LAST_ODO, end.odoKm.toString())
        reload()
        // 도착한 곳이 처음 가 보는 곳이면 먼저 저장할지 묻는다 (저장하면 목적 분류에 쓰인다)
        val arrival = Arrival(Arrival.Kind.DESTINATION, name = c.toPlace?.name.orEmpty(), lat = end.lat, lng = end.lng)
        if (!navi.providesArrival) flows.onArrived(arrival)
        else handler.postDelayed({
            // 내비가 도착을 알려 주지 않았으면 운행 종료 위치로 대신한다
            if (System.currentTimeMillis() - lastNaviArrival > NAVI_ARRIVAL_WAIT_MS * 2) flows.onArrived(arrival)
        }, NAVI_ARRIVAL_WAIT_MS)
        if (c.purpose == TripPurpose.UNCLASSIFIED) {
            askTripId = id
            lastNotice = "운행 ${fmtKm(trip.distanceKm)} 기록 · 목적 확인 필요"
            flows.askPurpose(trip.copy(id = id))
        } else {
            lastNotice = "${trip.purpose.label} ${fmtKm(trip.distanceKm)} 자동 기록 · ${c.reason}"
            speak("${trip.toName}, ${trip.purpose.label} 운행으로 기록했어요.")
        }
        notifyChanged()
    }

    private fun onEnergyEvent(e: EnergyEvent) {
        if (dataSuspended) return
        val ev = gateway.info.powertrain == Powertrain.EV
        val qty = e.deltaPercent / 100.0 * gateway.info.energyCapacity
        val expense = Expense(
            time = e.time,
            category = if (ev) ExpenseCategory.CHARGE else ExpenseCategory.FUEL,
            amountWon = 0, odoKm = odoKm, quantity = Math.round(qty * 10) / 10.0,
            memo = String.format(Locale.US, "%.0f%% → %.0f%%", e.fromPercent, e.toPercent),
            source = ExpenseSource.AUTO, pendingAmount = true,
        )
        askExpenseId = db.upsert(expense).also { record(Integrity.Kind.EXPENSE, it, Integrity.canonical(expense.copy(id = it))) }
        reload()
        lastNotice = if (ev) "충전 감지 · 약 ${fmt1(qty)}kWh" else "주유 감지 · 약 ${fmt1(qty)}L"
        flows.askAmount(expense.copy(id = askExpenseId ?: 0L))
        notifyChanged()
    }

    // ---- 사용자 입력 ----
    fun classifyTrip(id: Long, purpose: TripPurpose, by: ClassifiedBy) {
        if (!canEditTrip(id)) return
        val t = trips.firstOrNull { it.id == id } ?: return
        val fixed = t.copy(purpose = purpose, classifiedBy = by)
        db.upsert(fixed)
        record(Integrity.Kind.TRIP_FIX, id, Integrity.canonical(fixed))
        if (askTripId == id) askTripId = null
        reload()
        notifyChanged()
    }

    /** 운행기록부의 사용자란은 자동 추정하지 않고 등록된 이용자 중 실제 이용자를 확인해 기록한다. */
    fun confirmLogbookUser(id: Long, user: Driver?) {
        if (!canEditTrip(id)) return
        val t = trips.firstOrNull { it.id == id } ?: return
        val fixed = t.copy(
            logbookUser = user?.name.orEmpty(),
            logbookDepartment = user?.department.orEmpty(),
        )
        db.upsert(fixed)
        record(Integrity.Kind.TRIP_FIX, id, Integrity.canonical(fixed))
        reload()
        notifyChanged()
    }

    fun dismissTripQuestion() {
        if (dataSuspended) return
        askTripId = null
        notifyChanged()
    }

    fun setExpenseAmount(id: Long, amount: Long, source: ExpenseSource) {
        if (!canEditExpense(id)) return
        val e = expenses.firstOrNull { it.id == id } ?: return
        val fixed = e.copy(amountWon = amount, pendingAmount = false, source = if (e.source == ExpenseSource.AUTO) source else e.source)
        db.upsert(fixed)
        record(Integrity.Kind.EXPENSE_FIX, id, Integrity.canonical(fixed))
        if (askExpenseId == id) askExpenseId = null
        lastNotice = "${e.category.label} ${won(amount)} 장부 기록"
        if (::voice.isInitialized) voice.speak("${amountSpoken(amount)} 기록했어요.") { offerSoundAd() }
        reload()
        notifyChanged()
    }

    fun dismissExpenseQuestion() {
        if (dataSuspended) return
        askExpenseId = null
        notifyChanged()
    }

    fun saveExpense(e: Expense) {
        if (dataSuspended || isDriving || (e.id != 0L && !canEditExpense(e.id))) return
        val id = db.upsert(e)
        record(if (e.id == 0L) Integrity.Kind.EXPENSE else Integrity.Kind.EXPENSE_FIX, id, Integrity.canonical(e.copy(id = id)))
        reload()
        notifyChanged()
    }

    /** 확인 카드에서 저장을 누른 비용 초안을 장부에 넣는다 */
    fun saveDraft(d: ai.chagok.app.model.ExpenseDraft): Boolean {
        if (dataSuspended || isDriving) return false
        val amount = d.amountWon ?: return false
        val e = Expense(
            time = gateway.now(), category = d.category, amountWon = amount, odoKm = odoKm.takeIf { it > 0 },
            memo = d.memoText, source = d.source, shop = d.shop.trim(),
        )
        saveExpense(e)
        if (d.category == ExpenseCategory.MAINTENANCE) flows.onMaintenanceSaved(e.memo)
        lastNotice = "${d.category.label} ${won(amount)} 장부 기록"
        speak("${d.category.label} ${amountSpoken(amount)} 기록했어요.")
        notifyChanged()
        return true
    }

    fun deleteExpense(id: Long) {
        if (!canManage || isExpenseLocked(id)) return
        db.deleteExpense(id)
        record(Integrity.Kind.EXPENSE_DEL, id, "deleted|$id")
        reload()
        notifyChanged()
    }

    // ---- 보존 (제출 완료 확인) ----

    /** 보존기간(년). 기본·최소 5년이며 늘리기만 한다 */
    val retentionYears: Int get() = db.setting(KEY_RETENTION_YEARS).toIntOrNull()?.coerceAtLeast(Retention.MIN_YEARS) ?: Retention.MIN_YEARS

    fun setRetentionYears(years: Int) {
        if (!canManage || years < Retention.MIN_YEARS || years > 100) return
        db.setSetting(KEY_RETENTION_YEARS, years.toString())
        db.raiseRetention(years)
        reload()
        notifyChanged()
    }

    /** 제출에 포함될 운행·비용 수 (올해 [year] 의 운행, 금액이 정해진 비용) */
    fun submissionScope(year: Int): Pair<List<Long>, List<Long>> {
        val trips = trips.filter { yearOf(it.startTime) == year }.map { it.id }
        val expenses = expenses.filter { yearOf(it.time) == year && !it.pendingAmount }.map { it.id }
        return trips to expenses
    }

    private fun yearOf(t: Long) = java.util.Calendar.getInstance(ai.chagok.app.core.TaxReport.KST).apply { timeInMillis = t }.get(java.util.Calendar.YEAR)

    /**
     * 관리자가 실제 제출 완료를 확인했다. 확인한 시각이 기산점이다 (PDF 저장·공유·타임스탬프만으로는 호출되지 않는다).
     * 포함된 운행·비용은 이때부터 변경·삭제할 수 없다. 오류 문구를 돌려주고, 성공이면 null
     */
    fun confirmSubmission(year: Int, statutoryEnd: String, pdfName: String, pdfSha256: String): String? {
        if (!canManage || !admin.recentlyVerified()) return "관리자 PIN을 다시 확인해 주세요"
        if (!Retention.validDay(statutoryEnd)) return "법정 종료일은 2031-03-31 처럼 입력해 주세요"
        if (!integrity.ok) return "기록 무결성 검증이 맞지 않아 제출을 확인할 수 없어요"
        val (tripIds, expenseIds) = submissionScope(year)
        if (tripIds.isEmpty() && expenseIds.isEmpty()) return "${year}년에 제출할 기록이 없어요"
        db.addSubmission(
            Retention.Submission(
                confirmedAt = gateway.now(), year = year, tripIds = tripIds, expenseIds = expenseIds,
                pdfName = pdfName, pdfSha256 = pdfSha256, retentionYears = retentionYears, statutoryEnd = statutoryEnd,
            ),
        )
        reload()
        notifyChanged()
        return null
    }

    fun setStatutoryEnd(id: Long, day: String): Boolean {
        if (!canManage || !Retention.validDay(day)) return false
        db.setStatutoryEnd(id, day)
        reload()
        notifyChanged()
        return true
    }

    /** 만료일이 지난 제출. 알리기만 하고 자동으로 지우지 않는다 */
    fun expiredSubmissions(): List<Retention.Submission> = submissions.filter { Retention.expired(it, System.currentTimeMillis()) }

    // ---- 운행 단건 삭제 (미제출만) ----

    /**
     * 미제출 운행 한 건을 지우고 원장 payload·정정 이력의 원문을 파기한다. 호출 전에 화면이 영향 안내와 정확한 `삭제` 입력을 받아야 한다.
     * 앱 관리 백업·내보내기 사본의 파기는 호출한 쪽이 이어서 한다. 오류 문구를 돌려주고, 성공이면 null
     */
    fun deleteTrip(id: Long): String? {
        if (!canManage || !admin.recentlyVerified()) return "관리자 PIN을 다시 확인해 주세요"
        if (isTripLocked(id)) return "제출 완료로 확인된 운행은 삭제할 수 없어요"
        if (trips.none { it.id == id }) return "이미 삭제된 운행이에요"
        val entry = Integrity.append(db.ledger(), gateway.now(), Integrity.Kind.TRIP_DEL, id, "deleted|$id")
        db.purgeTrip(id, entry)
        if (currentTripId == id) currentTripId = null
        if (askTripId == id) askTripId = null
        assistant.clear()
        reload()
        notifyChanged()
        return null
    }

    fun savePlace(p: Place) {
        if (dataSuspended) return
        db.upsert(p)
        reload()
        notifyChanged()
    }

    fun deletePlace(id: Long) {
        if (dataSuspended) return
        db.deletePlace(id)
        reload()
        notifyChanged()
    }

    /** 현재 차 위치를 장소로 등록 (지도 없이도 거래처를 바로 추가) */
    fun currentLocation(): Pair<Double, Double> = lat to lng

    fun pendingTrip(): Trip? = askTripId?.let { id -> trips.firstOrNull { it.id == id && canEditTrip(id) } }
    fun pendingExpense(): Expense? = askExpenseId?.let { id -> expenses.firstOrNull { it.id == id } }

    companion object {
        const val KEY_SEEDED = "seeded"
        const val KEY_POWERTRAIN = "powertrain"
        const val KEY_LAST_ODO = "last_odo"
        const val KEY_DRIVER = "driver"
        /** 사업자 유형: personal(개인사업자, 소득세법 서식, 기본) · corp(법인, 법인세법 서식) */
        const val KEY_TAXPAYER = "taxpayer"
        const val KEY_COMPANY = "company"
        const val KEY_BIZNO = "bizno"
        const val KEY_DEPT = "dept"
        const val KEY_LAST_DRIVER = "last_driver"
        const val KEY_FORGED = "forged"
        /** 데모: 다음 가상 차량이 이어받을 "계기판,위도,경도,연료%" (한 번 쓰고 지운다) */
        const val KEY_SIM_STATE = "sim_state"
        /** 데모: 가상 시계의 마지막 시각. 다시 시동해도 시각이 뒤로 가지 않게 한다 */
        const val KEY_SIM_CLOCK = "sim_clock"
        /** 화면 테마: auto(시스템 주야간) · day · night */
        const val KEY_THEME = "theme"
        /** 타임스탬프 기관 전송: on(기본) · off */
        const val KEY_TSA = "tsa"
        /** 개인정보 처리 안내를 처음 실행 때 확인했는지 (실제 동작 모드) */
        const val KEY_PRIVACY_ACK = "privacy_ack"
        /** 제출 기록 보존기간(년): 5(기본, 최소)부터 늘리기만 한다 */
        const val KEY_RETENTION_YEARS = "retention_years"
        /** 개인정보 문의·권리 행사 연락처 (도입 사업장이 지정) */
        const val KEY_PRIVACY_CONTACT = "privacy_contact"
        /** 음성 사용: on(기본) · off */
        const val KEY_VOICE = "voice"

        /** 광고 모드: on · off(기본). 동의 시각, 소리 광고 "날짜|횟수". 운행·세무 데이터와 섞지 않는다 */
        const val KEY_AD = "ad_mode"
        const val KEY_AD_CONSENT = "ad_consent"
        const val KEY_AD_VOICE = "ad_voice"

        /** 이만큼 넘게 늘었을 때만 보정 운행으로 남긴다 (계기판 반올림 오차 제외) */
        const val GAP_MIN_KM = 0.2
        const val GAP_MEMO = "앱 꺼짐 구간 · 계기판 보정"

        private const val IDENTIFY_DELAY_MS = 1_500L
        private const val MAINT_CHECK_DELAY_MS = 4_000L
        private const val NAVI_ARRIVAL_WAIT_MS = 8_000L
        private const val GREETING_MS = 12_000L

        fun fmtKm(km: Double) = String.format(Locale.US, "%,.1f km", km)
        fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
        fun won(v: Long) = String.format(Locale.US, "%,d원", v)
        fun fmtKmSpoken(km: Double) = String.format(Locale.US, "%.1f킬로미터", km)
        fun amountSpoken(v: Long) = String.format(Locale.US, "%,d원", v)
    }
}
