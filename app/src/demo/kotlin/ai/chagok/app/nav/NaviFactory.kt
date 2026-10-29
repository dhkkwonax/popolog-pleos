package ai.chagok.app.nav

import android.content.Context

/** 데모 모드: 시뮬레이션 내비 */
object NaviFactory {
    fun create(context: Context): NaviBridge = SimulatedNavi()
}
