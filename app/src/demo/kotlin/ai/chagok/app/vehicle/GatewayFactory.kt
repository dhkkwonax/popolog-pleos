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
        return SimulatedVehicle(
            info = DemoSeed.vehicle(powertrain),
            // '앱 끈 채 운행' 시연이면 계기판만 그만큼 늘어난 채로 시동한다 (한 번 쓰고 지운다)
            startOdoKm = db.setting(AppController.KEY_SIM_ODO).toDoubleOrNull()?.also { db.setSetting(AppController.KEY_SIM_ODO, "") }
                ?: last?.endOdoKm ?: DemoSeed.START_ODO,
            startLevel = if (powertrain == Powertrain.EV) 34.0 else 27.0,
            startLat = last?.endLat ?: home.lat,
            startLng = last?.endLng ?: home.lng,
            startSeat = db.drivers().firstOrNull { it.name == last?.driver }?.seatPosition,
        )
    }
}
