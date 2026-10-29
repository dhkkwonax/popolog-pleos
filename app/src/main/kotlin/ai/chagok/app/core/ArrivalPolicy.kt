package ai.chagok.app.core

import ai.chagok.app.model.Place

/** 도착한 곳을 저장하자고 물어볼지 정한다 */
object ArrivalPolicy {
    /** 이 거리(m) 안은 같은 위치로 본다 */
    const val SAME_PLACE_M = 150.0

    data class Point(val lat: Double, val lng: Double)

    /**
     * 이미 저장된 장소 반경 안이거나, 이번에 저장하지 않기로 했거나 '다시 묻지 않기'를 고른 위치와 같으면 묻지 않는다.
     */
    fun shouldAsk(lat: Double, lng: Double, places: List<Place>, declined: List<Point>): Boolean {
        if (lat == 0.0 && lng == 0.0) return false
        if (places.any { Geo.distanceM(lat, lng, it.lat, it.lng) <= maxOf(it.radiusM.toDouble(), SAME_PLACE_M) }) return false
        return declined.none { Geo.distanceM(lat, lng, it.lat, it.lng) <= SAME_PLACE_M }
    }

    fun encode(points: List<Point>) = points.joinToString("\n") { "${it.lat},${it.lng}" }

    fun decode(s: String): List<Point> = s.lines().mapNotNull {
        val f = it.split(',')
        val la = f.getOrNull(0)?.toDoubleOrNull()
        val ln = f.getOrNull(1)?.toDoubleOrNull()
        if (la == null || ln == null) null else Point(la, ln)
    }
}
