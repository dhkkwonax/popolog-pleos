package ai.chagok.app.nav

/** 내비가 알려 주는 도착 */
data class Arrival(
    val kind: Kind,
    /** 내비의 목적지·경유지 이름. 모르면 빈 문자열 */
    val name: String = "",
    val address: String = "",
    /** 알면 좌표. 없으면 앱이 아는 현재 위치를 쓴다 */
    val lat: Double? = null,
    val lng: Double? = null,
) {
    enum class Kind { DESTINATION, WAYPOINT }
}

/** 장소를 저장할 때 이름 후보로 보여 주는 내비의 즐겨찾기·최근 목적지 */
data class PlaceCandidate(val name: String, val address: String, val lat: Double, val lng: Double, val source: String)

/** 내비(Pleos NaviHelper)와의 연결. 시연 모드는 [SimulatedNavi] 가 같은 이벤트를 흉내 낸다 */
interface NaviBridge {
    /** 도착 이벤트를 직접 주는 연결인지. false 면 앱이 운행 종료 위치로 도착을 판정한다 */
    val providesArrival: Boolean

    interface Listener {
        fun onArrived(a: Arrival)
        fun onCandidates(list: List<PlaceCandidate>)
    }

    fun start(listener: Listener)
    fun stop()
    fun refreshCandidates()
}

/** 내비 연결이 없는 실제 모드(내비 서비스가 없을 때): 이벤트를 보내지 않으므로 앱이 운행 종료 위치로 도착을 판정한다 */
class NoNavi : NaviBridge {
    override val providesArrival = false
    override fun start(listener: NaviBridge.Listener) = Unit
    override fun stop() = Unit
    override fun refreshCandidates() = Unit
}
