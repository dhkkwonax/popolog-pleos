package ai.chagok.app.vehicle

import ai.chagok.app.data.ChagokDb
import ai.chagok.app.model.Powertrain
import android.content.Context

/** Pleos SDK 빌드용: src/main 의 같은 파일을 대체한다 */
object GatewayFactory {
    fun create(context: Context, powertrain: Powertrain, db: ChagokDb): VehicleGateway =
        PleosVehicleGateway(context, powertrain)
}
