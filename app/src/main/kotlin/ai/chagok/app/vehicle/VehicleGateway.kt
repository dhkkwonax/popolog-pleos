package ai.chagok.app.vehicle

import ai.chagok.app.model.VehicleInfo

/**
 * 차량 데이터 출처. 실제 차량·Pleos 에뮬레이터에서는 [PleosVehicleGateway](src/real, 실제 동작 모드)가
 * Vehicle SDK(Odometer, Fuel, EvBattery, Brake, CarInfo)와 FusedLocation SDK를 감싸고,
 * 데모 모드(src/demo)에서는 [SimulatedVehicle]이 같은 신호를 흉내 낸다.
 */
interface VehicleGateway {
    val info: VehicleInfo
    val isSimulated: Boolean

    fun start(listener: Listener)
    fun stop()

    /** 시뮬레이터는 시간을 빠르게 돌리므로 기록 시각도 이 시계를 따른다 */
    fun now(): Long = System.currentTimeMillis()

    interface Listener {
        /** 계기판 누적 주행거리 (Odometer.getCurrentDistanceDriven) */
        fun onOdometer(km: Double)

        /** 연료량 % (Fuel.getFuelPercent) 또는 배터리 SoC % (EvBattery.getEvBatteryStateOfCharge) */
        fun onEnergyLevel(percent: Double)

        /** 주차 브레이크 (Brake) */
        fun onParkingBrake(engaged: Boolean)

        /** 위치 (FusedLocation.registerFusedLocationCallback). 음영지역에서는 null 이 올 수 있다 */
        fun onLocation(lat: Double, lng: Double)

        /** 운전석 앞뒤 위치 (Seat.getCurrentSeatForeAftPosition, ROW_1_LEFT). 운전자 판별에 쓴다 */
        fun onDriverSeat(position: Int) = Unit
    }
}
