package ai.chagok.app.vehicle

import ai.chagok.app.core.Geo
import ai.chagok.app.model.Powertrain
import ai.chagok.app.model.VehicleInfo
import android.os.Handler
import android.os.Looper

/**
 * 데모용 가상 차량. 주행 중에는 1초가 가상 2분(시속 60km)으로 흐른다.
 * 심사·시연 기기에 Pleos SDK가 없어도 자동 기록 흐름을 끝까지 보여 주기 위한 것.
 */
class SimulatedVehicle(
    override val info: VehicleInfo,
    startOdoKm: Double,
    startLevel: Double,
    startLat: Double,
    startLng: Double,
    startSeat: Int? = null,
    /** 가상 시계가 실제보다 앞선 만큼. 다시 시동해도 시각이 뒤로 가지 않게 이어 받는다 */
    startClockOffset: Long = 0L,
) : VehicleGateway {
    override val isSimulated = true

    var odoKm = startOdoKm
        private set
    var level = startLevel
        private set
    var lat = startLat
        private set
    var lng = startLng
        private set
    var parked = true
        private set

    /** 운전석 앞뒤 위치. 시연에서 다른 운전자가 타면 그 사람 위치로 바뀐다 */
    var seatPosition: Int? = startSeat
        private set
    val busy: Boolean get() = route != null || energyTarget != null
    val driving: Boolean get() = route != null
    val refilling: Boolean get() = energyTarget != null

    private val handler = Handler(Looper.getMainLooper())
    private var listener: VehicleGateway.Listener? = null
    private var clockOffset = startClockOffset

    /** 도중 정차: 이 시각(실제 uptime)까지 제자리에 서 있는다 */
    private var holdUntil = 0L

    private class Route(
        val fromLat: Double, val fromLng: Double, val toLat: Double, val toLng: Double,
        val roadKm: Double, var progressKm: Double = 0.0, val onArrive: () -> Unit,
    )

    private var route: Route? = null
    private var energyTarget: Double? = null
    private var onEnergyDone: (() -> Unit)? = null

    /**
     * 한 틱의 실제 간격. 스크린샷 자동 촬영에서만 [timeScale] 로 빨리 감는다.
     * 틱마다 움직이는 거리·가상 시각은 그대로라 기록 결과(거리·시각·분류)는 실제 시연과 같다.
     */
    private val tickMs: Long get() = (TICK_MS / timeScale.coerceAtLeast(1)).coerceAtLeast(16L)

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, tickMs)
        }
    }

    override fun now(): Long = System.currentTimeMillis() + clockOffset

    override fun start(listener: VehicleGateway.Listener) {
        this.listener = listener
        listener.onLocation(lat, lng)
        listener.onOdometer(odoKm)
        listener.onEnergyLevel(level)
        listener.onParkingBrake(true)
        seatPosition?.let { listener.onDriverSeat(it) }
        listener.onSpeed(0.0)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        listener = null
    }

    /** 현재 위치에서 목적지까지 주행. 도로 거리는 직선거리의 1.3배로 가정 */
    fun driveTo(toLat: Double, toLng: Double, onArrive: () -> Unit = {}) {
        if (busy) return
        val roadKm = Geo.distanceM(lat, lng, toLat, toLng) / 1000.0 * 1.3
        route = Route(lat, lng, toLat, toLng, roadKm.coerceAtLeast(0.5), onArrive = onArrive)
        parked = false
        listener?.onParkingBrake(false)
    }

    /** 시연: 다른 사람이 타서 운전석을 자기 위치로 맞춘다 (주차 중에만) */
    fun seatDriver(position: Int) {
        if (busy || !parked) return
        seatPosition = position
        listener?.onDriverSeat(position)
    }

    /**
     * 시연: 주행 중 신호 대기·정체처럼 가상 [minutes]분 동안 계기판이 그대로인 채 서 있다가 다시 간다.
     * 앱의 정차 타임아웃(5분)이 지나면 운행이 거기서 끊기고, 다시 출발하면 새 운행이 된다.
     */
    fun stopFor(minutes: Int) {
        if (route == null) return
        clockOffset += minutes * 60_000L
        // 앱의 1초 주기 확인이 한 번은 돌도록 실제로도 잠깐 멈춘다
        holdUntil = android.os.SystemClock.uptimeMillis() + 1_600L
    }

    /** 주유(%) 또는 충전(SoC %)을 목표치까지 올린다 */
    fun refill(targetPercent: Double, onDone: () -> Unit = {}) {
        if (busy || !parked) return
        energyTarget = targetPercent.coerceIn(level, 100.0)
        onEnergyDone = onDone
    }

    private fun step() {
        val l = listener ?: return
        route?.let { r ->
            if (android.os.SystemClock.uptimeMillis() < holdUntil) { l.onSpeed(0.0); return }
            l.onSpeed(SIM_SPEED_KMH)
            // 시속 60km 가정: 틱(0.25초) = 가상 30초 = 0.5km
            val stepKm = minOf(KM_PER_TICK, r.roadKm - r.progressKm)
            r.progressKm += stepKm
            clockOffset += VIRTUAL_MS_PER_TICK - tickMs
            odoKm += stepKm
            val f = r.progressKm / r.roadKm
            lat = r.fromLat + (r.toLat - r.fromLat) * f
            lng = r.fromLng + (r.toLng - r.fromLng) * f
            level = (level - consumptionPercent(stepKm)).coerceAtLeast(0.0)
            l.onLocation(lat, lng)
            l.onOdometer(round1(odoKm))
            l.onEnergyLevel(round1(level))
            if (r.progressKm >= r.roadKm) {
                route = null
                parked = true
                l.onParkingBrake(true)
                l.onSpeed(0.0)
                r.onArrive()
            }
            return
        }
        l.onSpeed(0.0)
        energyTarget?.let { target ->
            level = minOf(target, level + REFILL_PER_TICK)
            clockOffset += 20_000L
            l.onEnergyLevel(round1(level))
            if (level >= target) {
                energyTarget = null
                onEnergyDone?.invoke()
                onEnergyDone = null
            }
        }
    }

    private fun consumptionPercent(km: Double): Double = when (info.powertrain) {
        Powertrain.ICE -> km / 11.5 / info.energyCapacity * 100 // 11.5 km/L
        Powertrain.EV -> km * 0.165 / info.energyCapacity * 100 // 6.1 km/kWh
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    companion object {
        /** 빨리 감기 배율 (1 = 실시간 시연). 자동 촬영이 실행 인텐트의 sim_speed 로 정한다 */
        var timeScale = 1

        private const val SIM_SPEED_KMH = 60.0
        private const val TICK_MS = 250L
        private const val VIRTUAL_MS_PER_TICK = 30_000L
        private const val KM_PER_TICK = 0.5
        private const val REFILL_PER_TICK = 2.5
    }
}
