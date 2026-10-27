package ai.chagok.app.core

/** 운행 시작 시점의 계기판·위치 값 */
data class TripStart(val time: Long, val odoKm: Double, val lat: Double, val lng: Double)

data class TripEnd(val start: TripStart, val time: Long, val odoKm: Double, val lat: Double, val lng: Double)

/**
 * 계기판 누적거리(Odometer)와 주차 브레이크로 운행 구간을 자른다.
 * GPS 추정이 아니라 계기판 값이라 음영지역·앱 꺼짐으로 거리가 빠지지 않는다.
 *
 * - 시작: 마지막 주차 시점보다 누적거리가 [startThresholdKm] 이상 늘어남
 * - 종료: 주차 브레이크 체결, 또는 누적거리가 [idleTimeoutMs] 동안 그대로
 */
class TripRecorder(
    private val listener: Listener,
    private val startThresholdKm: Double = 0.1,
    private val idleTimeoutMs: Long = 5 * 60_000L,
) {
    interface Listener {
        fun onTripStarted(start: TripStart)
        fun onTripEnded(end: TripEnd)
    }

    private var parkedOdo: Double? = null
    private var lastOdo: Double? = null
    private var lastOdoChange = 0L
    private var lat = 0.0
    private var lng = 0.0
    private var hasLocation = false

    // 출발지는 감지 시점(이미 100m쯤 움직인 뒤)이 아니라 마지막으로 주차한 자리로 기록한다
    private var parkLat = 0.0
    private var parkLng = 0.0
    private var current: TripStart? = null

    val isDriving: Boolean get() = current != null
    val currentTrip: TripStart? get() = current

    fun onLocation(lat: Double, lng: Double) {
        if (!hasLocation) {
            parkLat = lat
            parkLng = lng
            hasLocation = true
        }
        this.lat = lat
        this.lng = lng
    }

    fun onOdometer(km: Double, now: Long) {
        if (lastOdo == null || km != lastOdo) lastOdoChange = now
        lastOdo = km
        val base = parkedOdo ?: km.also { parkedOdo = it }
        if (current == null && km - base >= startThresholdKm) {
            // 출발 지점의 계기판 값은 마지막 주차 때 값으로 기록한다
            current = TripStart(now, base, parkLat, parkLng).also(listener::onTripStarted)
        }
    }

    fun onParkingBrake(engaged: Boolean, now: Long) {
        if (engaged) finish(now)
    }

    /** 주기적으로 호출해 정차 타임아웃을 확인한다 */
    fun tick(now: Long) {
        if (current != null && now - lastOdoChange >= idleTimeoutMs) finish(now)
    }

    /** 앱 종료·시동 off 시 진행 중인 운행을 닫는다 */
    fun finish(now: Long) {
        val start = current ?: return
        val odo = lastOdo ?: start.odoKm
        current = null
        parkedOdo = odo
        parkLat = lat
        parkLng = lng
        listener.onTripEnded(TripEnd(start, now, odo, lat, lng))
    }
}

data class EnergyEvent(val time: Long, val fromPercent: Double, val toPercent: Double) {
    val deltaPercent: Double get() = toPercent - fromPercent
}

/**
 * 정차 중 연료량(Fuel %) 또는 배터리 SoC가 오르면 주유·충전으로 본다.
 * 값이 [settleMs] 동안 더 오르지 않거나 다시 출발하면 이벤트 1건으로 확정한다.
 */
class EnergyEventDetector(
    private val thresholdPercent: Double,
    private val settleMs: Long = 60_000L,
    private val onEvent: (EnergyEvent) -> Unit,
) {
    private var baseline: Double? = null
    private var last: Double? = null
    private var lastRise = 0L

    fun onLevel(percent: Double, now: Long, driving: Boolean) {
        val prev = last
        last = percent
        if (baseline == null) baseline = percent
        if (prev != null && percent > prev) lastRise = now
        // 주행 중 소모분은 기준값을 계속 낮춰 둔다
        if (driving || percent < baseline!!) {
            flush(now)
            baseline = percent
        }
    }

    fun tick(now: Long) {
        if (lastRise != 0L && now - lastRise >= settleMs) flush(now)
    }

    fun flush(now: Long) {
        val base = baseline ?: return
        val level = last ?: return
        if (level - base >= thresholdPercent) {
            onEvent(EnergyEvent(now, base, level))
        }
        baseline = level
        lastRise = 0L
    }
}
