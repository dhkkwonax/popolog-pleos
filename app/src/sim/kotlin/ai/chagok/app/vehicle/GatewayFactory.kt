package ai.chagok.app.vehicle

import ai.chagok.app.data.ChagokDb
import ai.chagok.app.data.DemoSeed
import ai.chagok.app.model.Powertrain
import android.content.Context

/**
 * 데모 APK는 항상 시뮬레이터를 쓴다.
 * Pleos SDK 빌드(src/pleos)에서는 이 파일 대신 PleosVehicleGateway를 돌려주는 버전을 쓴다.
 */
object GatewayFactory {
    fun create(context: Context, powertrain: Powertrain, db: ChagokDb): VehicleGateway {
        val last = db.trips().firstOrNull()
        val home = DemoSeed.HOME
        return SimulatedVehicle(
            info = DemoSeed.vehicle(powertrain),
            startOdoKm = last?.endOdoKm ?: DemoSeed.START_ODO,
            startLevel = if (powertrain == Powertrain.EV) 34.0 else 27.0,
            startLat = last?.endLat ?: home.lat,
            startLng = last?.endLng ?: home.lng,
            startSeat = db.drivers().firstOrNull { it.name == last?.driver }?.seatPosition,
        )
    }
}
