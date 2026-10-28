package ai.chagok.app.vehicle

import ai.chagok.app.data.ChagokDb
import ai.chagok.app.model.Powertrain
import android.content.Context

/** 실제 동작 모드(real 빌드): 에뮬레이터·실차의 Pleos SDK 값을 쓴다. src/demo 의 같은 파일 대신 들어간다 */
object GatewayFactory {
    fun create(context: Context, powertrain: Powertrain, db: ChagokDb): VehicleGateway =
        PleosVehicleGateway(context, powertrain)
}
