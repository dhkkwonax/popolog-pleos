package ai.chagok.app

import ai.chagok.app.core.DriverIdentifier
import ai.chagok.app.core.EnergyEvent
import ai.chagok.app.core.EnergyEventDetector
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

    private val ticker = object : Runnable {
        override fun run() {
            val now = gateway.now()
            recorder.tick(now)
            energy.tick(now)
            handler.postDelayed(this, 1_000L)
        }
    }

    val isDriving: Boolean get() = recorder.isDriving
    val liveTripKm: Double get() = recorder.currentTrip?.let { odoKm - it.odoKm } ?: 0.0

    val powertrain: Powertrain
        get() = if (db.setting(KEY_POWERTRAIN, Powertrain.ICE.name) == Powertrain.EV.name) Powertrain.EV else Powertrain.ICE

    fun start() {
        if (db.setting(KEY_SEEDED) != "1") {
            DemoSeed.seed(db, powertrain)
            db.setSetting(KEY_SEEDED, "1")
        }
        reload()
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.KOREAN
        }
        connectGateway()
        handler.post(ticker)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        if (::recorder.isInitialized) recorder.finish(gateway.now())
        gateway.stop()
        tts?.shutdown()
        tts = null
    }

    private fun connectGateway() {
        recorder = TripRecorder(this)
        energy = EnergyEventDetector(
            thresholdPercent = if (powertrain == Powertrain.EV) 3.0 else 5.0,
            onEvent = ::onEnergyEvent,
        )
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
        stopGatewayOnly()
        val p = powertrain
        db.clearAll()
        DemoSeed.seed(db, p)
        db.setSetting(KEY_POWERTRAIN, p.name)
        db.setSetting(KEY_SEEDED, "1")
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

    /** 차량 권한이 뒤늦게 승인되면 다시 연결한다 (Pleos 빌드의 런타임 권한) */
    fun reconnectGateway() {
        if (!::gateway.isInitialized) return
        stopGatewayOnly()
        connectGateway()
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
    }

    fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chagok")
    }

    // ---- VehicleGateway.Listener ----
    override fun onOdometer(km: Double) {
        odoKm = km
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

    /** 시뮬레이터에서 주유·충전이 끝났음을 알릴 때 */
    fun flushEnergy() = energy.flush(gateway.now())

    // ---- TripRecorder.Listener ----
    override fun onTripStarted(start: TripStart) {
        // 시연 자동 촬영이 logcat 으로 운행 시작·끝을 확인한다 (운행 중에는 화면 읽기가 느려 쓸 수 없다)
        android.util.Log.i("Popolog", "trip started")
        lastNotice = "운행 시작 · 계기판 ${fmtKm(start.odoKm)}"
        notifyChanged()
    }

    override fun onTripEnded(end: TripEnd) {
        android.util.Log.i("Popolog", "trip ended")
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
        askExpenseId = db.upsert(expense)
        reload()
        lastNotice = if (ev) "충전 감지 · 약 ${fmt1(qty)}kWh" else "주유 감지 · 약 ${fmt1(qty)}L"
        speak(if (ev) "충전이 감지됐어요. 결제 금액을 말씀해 주세요." else "주유가 감지됐어요. 결제 금액을 말씀해 주세요.")
        notifyChanged()
    }

    // ---- 사용자 입력 ----
    fun classifyTrip(id: Long, purpose: TripPurpose, by: ClassifiedBy) {
        val t = trips.firstOrNull { it.id == id } ?: return
        db.upsert(t.copy(purpose = purpose, classifiedBy = by))
        if (askTripId == id) askTripId = null
        reload()
        notifyChanged()
    }

    fun dismissTripQuestion() {
        askTripId = null
        notifyChanged()
    }

    fun setExpenseAmount(id: Long, amount: Long, source: ExpenseSource) {
        val e = expenses.firstOrNull { it.id == id } ?: return
        db.upsert(e.copy(amountWon = amount, pendingAmount = false, source = if (e.source == ExpenseSource.AUTO) source else e.source))
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
        db.upsert(e)
        reload()
        notifyChanged()
    }

    fun deleteExpense(id: Long) {
        db.deleteExpense(id)
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

        private const val IDENTIFY_DELAY_MS = 1_500L
        private const val GREETING_MS = 12_000L

        fun fmtKm(km: Double) = String.format(Locale.US, "%,.1f km", km)
        fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
        fun won(v: Long) = String.format(Locale.US, "%,d원", v)
        fun fmtKmSpoken(km: Double) = String.format(Locale.US, "%.1f킬로미터", km)
        fun amountSpoken(v: Long) = String.format(Locale.US, "%,d원", v)
    }
}
