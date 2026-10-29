package ai.chagok.app

import ai.chagok.app.core.DriverIdentifier
import ai.chagok.app.core.EnergyEvent
import ai.chagok.app.core.EnergyEventDetector
import ai.chagok.app.core.Integrity
import ai.chagok.app.core.Rfc3161
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
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 차량 신호 → 운행·비용 자동 기록의 중심.
 * 화면은 [addListener]로 변경 알림을 받아 다시 그린다.
 */
class AppController(private val context: Context) : VehicleGateway.Listener, TripRecorder.Listener {
    val db = ChagokDb(context)
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
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var tickDay: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            val now = gateway.now()
            recorder.tick(now)
            energy.tick(now)
            // 날짜가 바뀌면 지난 날을 마감한다
            val day = Integrity.dayOf(now)
            if (day != tickDay) {
                if (tickDay != null) anchorPastDays(now)
                tickDay = day
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    val isDriving: Boolean get() = recorder.isDriving
    val liveTripKm: Double get() = recorder.currentTrip?.let { odoKm - it.odoKm } ?: 0.0

    val powertrain: Powertrain
        get() = if (db.setting(KEY_POWERTRAIN, Powertrain.ICE.name) == Powertrain.EV.name) Powertrain.EV else Powertrain.ICE

    /** 데모 모드(가상 차량·예시 데이터·시연 오버레이) 빌드인지. 실제 동작 모드는 Pleos SDK 값만 쓴다 */
    val demo: Boolean get() = BuildConfig.DEMO

    /** 외부 타임스탬프 기관에 날짜·해시 지문을 보낼지. 설정에서 끌 수 있고, 꺼 두면 앵커는 응답 대기로 남는다 */
    val tsaEnabled: Boolean get() = db.setting(KEY_TSA, "on") != "off"

    fun setTsaEnabled(on: Boolean) {
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
        if (demo && db.setting(KEY_SEEDED) != "1") {
            DemoSeed.seed(db, powertrain)
            db.setSetting(KEY_SEEDED, "1")
        }
        ensureLedger()
        // 앱이 꺼져 있던 동안 지난 날도 마감한다
        anchorPastDays(System.currentTimeMillis())
        reload()
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.KOREAN
        }
        connectGateway()
        handler.post(ticker)
        stampPending()
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(identifyOnIgnition)
        handler.removeCallbacks(clearGreeting)
        if (::recorder.isInitialized) recorder.finish(gateway.now())
        // 다음 실행 때 꺼져 있던 동안의 거리를 잴 기준
        if (odoKm > 0) db.setSetting(KEY_LAST_ODO, odoKm.toString())
        if (demo) saveSimState()
        gateway.stop()
        tts?.shutdown()
        tts = null
    }

    /** [fresh] 가 false 면 진행 중인 운행·주유 감지 상태를 이어 간다 (같은 차에 다시 붙을 때) */
    private fun connectGateway(fresh: Boolean = true) {
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
        val origin = ai.chagok.app.core.Geo.nearestPlace(lat, lng, places)?.name.orEmpty()
        val r = DriverIdentifier.identify(drivers, seatPosition, gateway.now(), origin, trips, db.setting(KEY_LAST_DRIVER)) ?: return
        applyDriver(r)
    }

    /** 머리말이나 설정에서 직접 고를 때 */
    fun chooseDriver(id: String) {
        val d = drivers.firstOrNull { it.id == id } ?: return
        applyDriver(DriverIdentifier.Result(d, DriverMethod.MANUAL))
    }

    private fun applyDriver(r: DriverIdentifier.Result) {
        driver = r
        seatAtIdentify = seatPosition
        db.setSetting(KEY_LAST_DRIVER, r.driver.id)
        driverGreeting = if (r.method == DriverMethod.MANUAL) "${r.driver.name}님으로 바꿨어요" else "${r.driver.name}님으로 인식했어요"
        if (r.method != DriverMethod.MANUAL) speak("${r.driver.name}님, 안녕하세요.")
        handler.removeCallbacks(clearGreeting)
        handler.postDelayed(clearGreeting, GREETING_MS)
        notifyChanged()
    }

    /** 운전자 등록 (실제 동작 모드). 처음 등록한 사람은 바로 지금 운전자로 둔다 */
    fun addDriver(name: String, department: String) {
        val d = Driver("d" + System.currentTimeMillis().toString(36), name, department, null)
        drivers = drivers + d
        db.saveDrivers(drivers)
        if (driver == null) chooseDriver(d.id) else notifyChanged()
    }

    /** 지금 운전석 위치를 이 사람의 위치로 저장 (다음 시동부터 시트로 알아본다) */
    fun saveSeatFor(id: String) {
        val pos = seatPosition ?: return
        drivers = drivers.map { if (it.id == id) it.copy(seatPosition = pos) else it }
        db.saveDrivers(drivers)
        notifyChanged()
    }

    /** 데모 설정에서 가솔린 ↔ 전기차 전환 */
    fun switchPowertrain(p: Powertrain) {
        if (p == powertrain) return
        stopGatewayOnly()
        db.setSetting(KEY_POWERTRAIN, p.name)
        db.setSetting(KEY_LAST_ODO, "")
        connectGateway()
        notifyChanged()
    }

    fun resetDemo() {
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

    /**
     * 실제 동작 모드의 '내 데이터 모두 삭제': 운행·비용·장소·운전자·해시체인·앵커·설정을 모두 지운다. 되돌릴 수 없다.
     * 내보낸 PDF·CSV·검증 파일은 앱 밖(다운로드/Popolog)에 있어 지우지 않는다.
     */
    fun wipeAllData() {
        if (demo) return
        stopGatewayOnly()
        db.clearAll()
        driver = null
        driverGreeting = null
        askTripId = null
        askExpenseId = null
        lastNotice = ""
        odoKm = 0.0
        reload()
        connectGateway()
        notifyChanged()
    }

    private fun stopGatewayOnly() {
        gateway.stop()
    }

    /** 차량 권한이 뒤늦게 승인되면 다시 연결한다 (Pleos 빌드의 런타임 권한). 주행 중이어도 운행은 끊지 않는다 */
    fun reconnectGateway() {
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
        if (!tsaEnabled) return
        val pending = db.anchors().filter { it.receipt.isEmpty() }
        if (pending.isEmpty() || stamping) return
        stamping = true
        notifyChanged()
        tsaExecutor.execute {
            var ok = 0
            for (a in pending) {
                val receipt = Rfc3161.stamp(a.hash, a.day) ?: continue
                val t = Rfc3161.check(receipt, a.hash, a.day)?.time ?: a.stampedAt
                db.saveAnchor(a.copy(receipt = receipt, stampedAt = t))
                ok++
            }
            if (demo) android.util.Log.i("Popolog", "tsa stamped $ok/${pending.size}")
            handler.post {
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
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chagok")
    }

    // ---- VehicleGateway.Listener ----
    override fun onOdometer(km: Double) {
        odoKm = km
        if (checkGap) {
            checkGap = false
            recordOfflineGap(km)
        }
        recorder.onOdometer(km, gateway.now())
        notifyChanged()
    }

    override fun onEnergyLevel(percent: Double) {
        energyPercent = percent
        energy.onLevel(percent, gateway.now(), recorder.isDriving)
        notifyChanged()
    }

    override fun onParkingBrake(engaged: Boolean) {
        parkingBrake = engaged
        recorder.onParkingBrake(engaged, gateway.now())
        notifyChanged()
    }

    override fun onLocation(lat: Double, lng: Double) {
        this.lat = lat
        this.lng = lng
        recorder.onLocation(lat, lng)
    }

    override fun onDriverSeat(position: Int) {
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
    fun flushEnergy() = energy.flush(gateway.now())

    // ---- TripRecorder.Listener ----
    override fun onTripStarted(start: TripStart) {
        // 시연 자동 촬영이 logcat 으로 운행 시작·끝을 확인한다 (운행 중에는 화면 읽기가 느려 쓸 수 없다)
        if (demo) android.util.Log.i("Popolog", "trip started")
        lastNotice = "운행 시작 · 계기판 ${fmtKm(start.odoKm)}"
        notifyChanged()
    }

    override fun onTripEnded(end: TripEnd) {
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
        record(Integrity.Kind.TRIP, id, Integrity.canonical(trip.copy(id = id)))
        db.setSetting(KEY_LAST_ODO, end.odoKm.toString())
        reload()
        if (c.purpose == TripPurpose.UNCLASSIFIED) {
            askTripId = id
            lastNotice = "운행 ${fmtKm(trip.distanceKm)} 기록 · 목적 확인 필요"
            speak("${fmtKmSpoken(trip.distanceKm)} 운행을 기록했어요. 업무 운행이었나요?")
        } else {
            lastNotice = "${trip.purpose.label} ${fmtKm(trip.distanceKm)} 자동 기록 · ${c.reason}"
            speak("${trip.toName}, ${trip.purpose.label} 운행으로 기록했어요.")
        }
        notifyChanged()
    }

    private fun onEnergyEvent(e: EnergyEvent) {
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
        speak(if (ev) "충전이 감지됐어요. 결제 금액을 말씀해 주세요." else "주유가 감지됐어요. 결제 금액을 말씀해 주세요.")
        notifyChanged()
    }

    // ---- 사용자 입력 ----
    fun classifyTrip(id: Long, purpose: TripPurpose, by: ClassifiedBy) {
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
        askTripId = null
        notifyChanged()
    }

    fun setExpenseAmount(id: Long, amount: Long, source: ExpenseSource) {
        val e = expenses.firstOrNull { it.id == id } ?: return
        val fixed = e.copy(amountWon = amount, pendingAmount = false, source = if (e.source == ExpenseSource.AUTO) source else e.source)
        db.upsert(fixed)
        record(Integrity.Kind.EXPENSE_FIX, id, Integrity.canonical(fixed))
        if (askExpenseId == id) askExpenseId = null
        lastNotice = "${e.category.label} ${won(amount)} 장부 기록"
        speak("${amountSpoken(amount)} 기록했어요.")
        reload()
        notifyChanged()
    }

    fun dismissExpenseQuestion() {
        askExpenseId = null
        notifyChanged()
    }

    fun saveExpense(e: Expense) {
        val id = db.upsert(e)
        record(if (e.id == 0L) Integrity.Kind.EXPENSE else Integrity.Kind.EXPENSE_FIX, id, Integrity.canonical(e.copy(id = id)))
        reload()
        notifyChanged()
    }

    fun deleteExpense(id: Long) {
        db.deleteExpense(id)
        record(Integrity.Kind.EXPENSE_DEL, id, "deleted|$id")
        reload()
        notifyChanged()
    }

    fun savePlace(p: Place) {
        db.upsert(p)
        reload()
        notifyChanged()
    }

    fun deletePlace(id: Long) {
        db.deletePlace(id)
        reload()
        notifyChanged()
    }

    /** 현재 차 위치를 장소로 등록 (지도 없이도 거래처를 바로 추가) */
    fun currentLocation(): Pair<Double, Double> = lat to lng

    fun pendingTrip(): Trip? = askTripId?.let { id -> trips.firstOrNull { it.id == id } }
    fun pendingExpense(): Expense? = askExpenseId?.let { id -> expenses.firstOrNull { it.id == id } }

    companion object {
        const val KEY_SEEDED = "seeded"
        const val KEY_POWERTRAIN = "powertrain"
        const val KEY_LAST_ODO = "last_odo"
        const val KEY_DRIVER = "driver"
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

        /** 이만큼 넘게 늘었을 때만 보정 운행으로 남긴다 (계기판 반올림 오차 제외) */
        const val GAP_MIN_KM = 0.2
        const val GAP_MEMO = "앱 꺼짐 구간 · 계기판 보정"

        private const val IDENTIFY_DELAY_MS = 1_500L
        private const val GREETING_MS = 12_000L

        fun fmtKm(km: Double) = String.format(Locale.US, "%,.1f km", km)
        fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
        fun won(v: Long) = String.format(Locale.US, "%,d원", v)
        fun fmtKmSpoken(km: Double) = String.format(Locale.US, "%.1f킬로미터", km)
        fun amountSpoken(v: Long) = String.format(Locale.US, "%,d원", v)
    }
}
