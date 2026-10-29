package ai.chagok.app.nav

import ai.chagok.app.data.DemoSeed

/** 시연용 내비: 도착 이벤트는 시연 컨트롤에서 직접 보내고, 즐겨찾기·최근 목적지는 샘플을 준다 */
class SimulatedNavi : NaviBridge {
    override val providesArrival = false
    private var listener: NaviBridge.Listener? = null

    override fun start(listener: NaviBridge.Listener) {
        this.listener = listener
        refreshCandidates()
    }

    override fun stop() {
        listener = null
    }

    override fun refreshCandidates() {
        listener?.onCandidates(
            listOf(
                PlaceCandidate("스타필드 하남", "경기 하남시 미사대로 750", DemoSeed.MALL_LAT, DemoSeed.MALL_LNG, "즐겨찾기"),
                PlaceCandidate(DemoSeed.MALL_NAME, "경기 하남시 하남대로", DemoSeed.MALL_LAT, DemoSeed.MALL_LNG, "최근 목적지"),
                PlaceCandidate(DemoSeed.REST_NAME, "경기 하남시 미사대로", DemoSeed.REST_LAT, DemoSeed.REST_LNG, "즐겨찾기"),
            ),
        )
    }

    /** 시연: 경유지(또는 목적지)에 도착했다는 내비 이벤트를 보낸다 */
    fun arrive(a: Arrival) {
        listener?.onArrived(a)
    }
}
