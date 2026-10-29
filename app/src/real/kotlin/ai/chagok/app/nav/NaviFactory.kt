package ai.chagok.app.nav

import android.content.Context

/** 실제 동작 모드: Pleos 내비(NaviHelper). 연결할 수 없으면 이벤트 없는 연결로 두고 앱이 위치로 도착을 판정한다 */
object NaviFactory {
    fun create(context: Context): NaviBridge = runCatching<NaviBridge> { PleosNaviBridge(context) }.getOrElse { NoNavi() }
}
