package ai.chagok.app.core

import ai.chagok.app.model.Place
import ai.chagok.app.model.PlaceKind
import ai.chagok.app.model.TripPurpose
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** 반경 안에 들어오는 장소 중 가장 가까운 곳 */
    fun nearestPlace(lat: Double, lng: Double, places: List<Place>): Place? =
        places
            .map { it to distanceM(lat, lng, it.lat, it.lng) }
            .filter { (place, d) -> d <= place.radiusM }
            .minByOrNull { it.second }
            ?.first
}

data class Classification(
    val purpose: TripPurpose,
    val fromPlace: Place?,
    val toPlace: Place?,
    val reason: String,
)

/**
 * 내비 목적지 없이 출발·도착 위치만으로 운행 목적을 분류한다.
 *
 * 1. 도착지가 거래처 반경 안 → 일반업무
 * 2. 출발지가 거래처이고 도착지가 회사 → 일반업무 (거래처 방문 후 복귀)
 * 3. 집 ↔ 회사 → 출퇴근
 * 4. 그 외 → 미분류 (도착 직후 음성으로 한 번 물어본다)
 */
object TripClassifier {
    fun classify(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        places: List<Place>,
    ): Classification {
        val from = Geo.nearestPlace(startLat, startLng, places)
        val to = Geo.nearestPlace(endLat, endLng, places)
        return when {
            to?.kind == PlaceKind.CLIENT ->
                Classification(TripPurpose.BUSINESS, from, to, "도착지가 거래처(${to.name}) 반경 ${to.radiusM}m 안")
            from?.kind == PlaceKind.CLIENT && to?.kind == PlaceKind.OFFICE ->
                Classification(TripPurpose.BUSINESS, from, to, "거래처(${from.name}) 방문 후 회사 복귀")
            from?.kind == PlaceKind.HOME && to?.kind == PlaceKind.OFFICE ->
                Classification(TripPurpose.COMMUTE, from, to, "집 → 회사")
            from?.kind == PlaceKind.OFFICE && to?.kind == PlaceKind.HOME ->
                Classification(TripPurpose.COMMUTE, from, to, "회사 → 집")
            to != null ->
                Classification(TripPurpose.UNCLASSIFIED, from, to, "도착지(${to.name})는 목적을 정하지 않은 장소")
            else ->
                Classification(TripPurpose.UNCLASSIFIED, from, to, "등록된 장소와 일치하지 않음")
        }
    }
}
