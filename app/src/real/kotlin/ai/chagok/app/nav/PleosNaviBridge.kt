package ai.chagok.app.nav

import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Pleos 내비(NaviHelper)의 도착 이벤트와 즐겨찾기·최근 목적지를 앱 이벤트로 옮긴다 */
class PleosNaviBridge(context: Context) : NaviBridge {
    override val providesArrival = true
    private val main = Handler(Looper.getMainLooper())
    private val navi = NaviHelper(context.applicationContext)
    private var listener: NaviBridge.Listener? = null
    private var destination: DestinationInfo? = null
    private var bookmarks: List<PlaceCandidate> = emptyList()
    private var recents: List<PlaceCandidate> = emptyList()

    private val events = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            destination = destinationInfo
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            val d = destination
            main.post {
                listener?.onArrived(Arrival(Arrival.Kind.DESTINATION, destinationArrivedInfo.name, destinationArrivedInfo.address, d?.latitude, d?.longitude))
            }
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            // 경유지 도착 이벤트에는 좌표가 없어 앱이 아는 현재 위치를 쓴다
            main.post { listener?.onArrived(Arrival(Arrival.Kind.WAYPOINT, waypointArrivedInfo.name, waypointArrivedInfo.address)) }
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            bookmarks = bookmarkInfoList.map { PlaceCandidate(it.name, it.address, it.latitude, it.longitude, "즐겨찾기") }
            publish()
        }

        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            recents = recentDestinationInfoList.map { PlaceCandidate(it.name, it.address, it.latitude, it.longitude, "최근 목적지") }
            publish()
        }
    }

    private fun publish() {
        val all = bookmarks + recents
        main.post { listener?.onCandidates(all) }
    }

    override fun start(listener: NaviBridge.Listener) {
        this.listener = listener
        runCatching {
            navi.initialize()
            navi.addListener(events)
            refreshCandidates()
            Log.i(TAG, "navi helper started")
        }.onFailure { Log.w(TAG, "navi helper unavailable", it) }
    }

    override fun refreshCandidates() {
        runCatching {
            navi.getBookmarkInfo()
            navi.getRecentDestinationInfo()
            navi.getDestinationInfo()
        }
    }

    override fun stop() {
        listener = null
        runCatching { navi.removeListener(events) }
        runCatching { navi.release() }
    }

    private companion object {
        const val TAG = "PopologNavi"
    }
}
