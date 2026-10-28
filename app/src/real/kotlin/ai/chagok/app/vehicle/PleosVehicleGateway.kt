package ai.chagok.app.vehicle

// Pleos SDK 빌드 전용 소스 (데모 APK에는 포함되지 않음).
// nexus-playground.pleos.ai 저장소에 접근 가능한 환경에서 AGP 프로젝트로 옮겨 빌드한다. README 참고.
//
// - FusedLocation 호출은 공식 샘플(Pleos-Playground/pleos-sample-fusedlocation-app)과 같은 형태다.
// - Vehicle 도메인 getter의 onComplete/onFailed 형태는 공식 Vehicle 샘플의 Door 호출을 따랐다.
//   반환 타입은 Vehicle 2.3.2로 확정: Odometer → Float?, Fuel·EvBattery SoC → Int?, CarInfo CRN·VIN → String?

import ai.chagok.app.model.Powertrain
import ai.chagok.app.model.VehicleInfo
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatForeAftPositionListener
import android.content.Context
import android.os.Handler
import android.os.Looper

class PleosVehicleGateway(context: Context, powertrain: Powertrain) : VehicleGateway {
    override val isSimulated = false
    override var info = VehicleInfo("Pleos 차량", "", "", powertrain, if (powertrain == Powertrain.EV) 63.0 else 64.0)
        private set

    private val vehicle = Vehicle(context.applicationContext)
    private val location = FusedLocation(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: VehicleGateway.Listener? = null

    /** 설정값으로 시작하되, 차에 없는 속성이면 연료 ↔ 배터리를 한 번 바꿔 잡는다 */
    private var readsBattery = powertrain == Powertrain.EV
        set(value) {
            if (field == value) return
            field = value
            // 화면의 "연료/배터리" 표시도 실제로 읽히는 쪽을 따라간다
            info = info.copy(powertrain = if (value) Powertrain.EV else Powertrain.ICE)
        }

    /** 운전석(왼쪽 앞) 앞뒤 위치. 시트를 움직이면 다른 운전자가 탄 것인지 다시 판별한다 */
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val seatListener = object : SeatForeAftPositionListener {
        override fun onSeatForeAftPositionUpdated(position: Int?) {
            position ?: return
            post { listener?.onDriverSeat(position) }
        }

        // 시트 신호가 없는 차종이면 시트 없이 판별한다 (시간·장소·마지막 운전자)
        override fun onFailed(exception: Exception) = Unit
    }

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            // 음영지역 등에서 null이 오면 직전 위치를 유지한다 (거리는 계기판 값이라 영향 없음)
            location ?: return
            post { listener?.onLocation(location.latitude, location.longitude) }
        }

        override fun onError(exception: Exception) = Unit
    }

    /** Odometer는 변경 리스너가 없어 2초마다 읽는다 */
    private val poll = object : Runnable {
        override fun run() {
            readOdometer()
            readEnergy()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun start(listener: VehicleGateway.Listener) {
        this.listener = listener
        vehicle.initialize()
        readCarInfo()
        readDriverSeat()
        // FUSED_LOCATION 은 dangerous 권한이라 아직 승인 전이면 SecurityException 이 난다.
        // 위치 없이도 거리·연료 기록은 계속돼야 하므로 삼키고, 승인되면 reconnectGateway 로 다시 붙는다.
        runCatching {
            if (location.isSupported()) {
                location.initialize()
                location.registerFusedLocationCallback(locationListener)
                location.getLastKnownLocation(locationListener)
            }
        }
        handler.post(poll)
    }

    override fun stop() {
        handler.removeCallbacks(poll)
        runCatching { vehicle.getSeat().unregisterSeatForeAftPosition(driverSeat, seatListener) }
        runCatching { location.unregisterFusedLocationCallback(locationListener) }
        runCatching { location.release() }
        runCatching { vehicle.release() }
        listener = null
    }

    private fun readOdometer() {
        vehicle.getOdometer().getCurrentDistanceDriven(
            onComplete = { km: Float? -> km?.let { v -> post { listener?.onOdometer(v.toDouble()) } } },
            onFailed = { },
        )
    }

    private fun readEnergy() {
        if (readsBattery) readStateOfCharge() else readFuelPercent()
    }

    private fun readStateOfCharge() = vehicle.getEvBattery().getEvBatteryStateOfCharge(
        onComplete = { soc: Int? -> soc?.let { v -> post { listener?.onEnergyLevel(v.toDouble()) } } },
        onFailed = { readsBattery = false },
    )

    private fun readFuelPercent() = vehicle.getFuel().getFuelPercent(
        onComplete = { p: Int? -> p?.let { v -> post { listener?.onEnergyLevel(v.toDouble()) } } },
        // 전기차에는 FUEL_PERCENT 속성이 없다(에뮬레이터도 마찬가지). 그럴 때는 SoC로 넘어간다
        onFailed = { readsBattery = true },
    )

    private fun readDriverSeat() {
        vehicle.getSeat().getCurrentSeatForeAftPosition(
            driverSeat,
            onComplete = { pos: Int? -> pos?.let { v -> post { listener?.onDriverSeat(v) } } },
            onFailed = { },
        )
        runCatching { vehicle.getSeat().registerSeatForeAftPosition(driverSeat, seatListener) }
    }

    private fun readCarInfo() {
        // CRN = 자동차등록번호, VIN = 차대번호 → 운행기록부 머리말
        vehicle.getCarInfo().getCRNInfo(
            onComplete = { crn: String? -> post { info = info.copy(plateNumber = plausible(crn)) } },
            onFailed = { },
        )
        vehicle.getCarInfo().getVINInfo(
            onComplete = { vin: String? -> post { info = info.copy(vin = plausible(vin)) } },
            onFailed = { },
        )
    }

    /**
     * 에뮬레이터는 등록되지 않은 값을 "invalid" 라는 문자열로 돌려준다.
     * 국세청 운행기록부 머리말에 그대로 찍히면 안 되므로 빈 값으로 둔다.
     */
    private fun plausible(raw: String?): String {
        val v = raw.orEmpty().trim().trim('"')
        return if (v.isEmpty() || v.equals("invalid", ignoreCase = true)) "" else v
    }

    /** SDK 콜백은 바인더 스레드로 오므로 UI를 건드리기 전에 메인으로 옮긴다 */
    private fun post(block: () -> Unit) = handler.post(block)

    private companion object {
        const val POLL_MS = 2_000L
    }
}
