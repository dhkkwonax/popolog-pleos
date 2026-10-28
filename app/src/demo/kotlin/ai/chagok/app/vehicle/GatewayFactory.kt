package ai.chagok.app.vehicle

import ai.chagok.app.AppController
import ai.chagok.app.data.ChagokDb
import ai.chagok.app.data.DemoSeed
import ai.chagok.app.model.Powertrain
import android.content.Context

/**
 * 데모 모드(demo 빌드)는 항상 가상 차량을 쓴다.
 * 실제 동작 모드(real 빌드, src/real)에서는 이 파일 대신 PleosVehicleGateway를 돌려주는 버전을 쓴다.
 */
object GatewayFactory {
    fun create(context: Context, powertrain: Powertrain, db: ChagokDb): VehicleGateway {
        val last = db.trips().firstOrNull()
        val home = DemoSeed.HOME
        // 시동을 끈 자리의 계기판·위치·연료 (시동 끄고 켜기·앱 끈 채 운행·앱 재시작). 한 번 쓰고 지운다
        val st = db.setting(AppController.KEY_SIM_STATE).split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 4 }
        db.setSetting(AppController.KEY_SIM_STATE, "")
        val clock = db.setting(AppController.KEY_SIM_CLOCK).toLongOrNull() ?: 0L
        return SimulatedVehicle(
            info = DemoSeed.vehicle(powertrain),
            startOdoKm = st?.get(0) ?: last?.endOdoKm ?: DemoSeed.START_ODO,
            startLevel = st?.get(3) ?: if (powertrain == Powertrain.EV) 34.0 else 27.0,
            startLat = st?.get(1) ?: last?.endLat ?: home.lat,
            startLng = st?.get(2) ?: last?.endLng ?: home.lng,
            startSeat = db.drivers().firstOrNull { it.name == last?.driver }?.seatPosition,
            startClockOffset = (clock - System.currentTimeMillis()).coerceAtLeast(0L),
        )
    }
}
