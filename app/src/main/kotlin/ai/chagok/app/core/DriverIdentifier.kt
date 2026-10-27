package ai.chagok.app.core

import ai.chagok.app.model.Driver
import ai.chagok.app.model.DriverMethod
import ai.chagok.app.model.Trip
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * 시동 직후 누가 운전석에 앉았는지 추정한다.
 *
 * Pleos SDK 에는 운전자·사용자 프로필 API 가 없고(프로필 서비스는 시스템 앱 전용),
 * Android 사용자도 차량 하나에 '운전자' 한 명뿐이라, 앱 안의 운전자 프로필과 차량 신호로 판별한다.
 *  1) 운전석 시트 앞뒤 위치(Vehicle SDK Seat)가 등록된 위치와 가까우면 그 사람
 *  2) 아니면 과거 운행에서 같은 출발 장소·비슷한 시각에 가장 많이 몬 사람
 *  3) 그래도 모르면 마지막 운전자
 */
object DriverIdentifier {
    /** 시트 위치 허용 오차 (SDK 시트 위치 단위) */
    const val SEAT_TOLERANCE = 6

    /** 출발 시각이 이만큼(시간) 안이면 같은 습관으로 본다 */
    const val HOUR_WINDOW = 1

    data class Result(val driver: Driver, val method: DriverMethod)

    fun identify(
        drivers: List<Driver>,
        seatPosition: Int?,
        startTime: Long,
        originName: String,
        history: List<Trip>,
        lastDriverId: String?,
        tz: TimeZone = TaxReport.KST,
    ): Result? {
        if (drivers.isEmpty()) return null

        bySeat(drivers, seatPosition)?.let { return Result(it, DriverMethod.SEAT) }
        byRoutine(drivers, startTime, originName, history, tz)?.let { return Result(it, DriverMethod.ROUTINE) }
        val last = drivers.firstOrNull { it.id == lastDriverId } ?: drivers.first()
        return Result(last, DriverMethod.LAST)
    }

    /** 등록 위치가 허용 오차 안에 있는 사람 중 가장 가까운 사람. 같은 거리로 둘이면 판단하지 않는다 */
    fun bySeat(drivers: List<Driver>, seatPosition: Int?): Driver? {
        seatPosition ?: return null
        val near = drivers.mapNotNull { d -> d.seatPosition?.let { d to abs(it - seatPosition) } }
            .filter { it.second <= SEAT_TOLERANCE }
            .sortedBy { it.second }
        if (near.isEmpty()) return null
        if (near.size > 1 && near[0].second == near[1].second) return null
        return near[0].first
    }

    /** 같은 출발 장소에서 ±[HOUR_WINDOW]시간 안에 출발한 과거 운행이 가장 많은 사람. 동점이면 판단하지 않는다 */
    fun byRoutine(drivers: List<Driver>, startTime: Long, originName: String, history: List<Trip>, tz: TimeZone): Driver? {
        if (originName.isEmpty()) return null
        val hour = hourOf(startTime, tz)
        val counts = drivers.associateWith { d ->
            history.count { t ->
                t.driver == d.name && t.fromName == originName && hourDistance(hourOf(t.startTime, tz), hour) <= HOUR_WINDOW
            }
        }.entries.sortedByDescending { it.value }
        val top = counts.firstOrNull() ?: return null
        if (top.value == 0) return null
        if (counts.size > 1 && counts[1].value == top.value) return null
        return top.key
    }

    private fun hourOf(t: Long, tz: TimeZone) = Calendar.getInstance(tz).apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

    /** 23시와 0시는 1시간 차이 */
    private fun hourDistance(a: Int, b: Int) = minOf(abs(a - b), 24 - abs(a - b))
}
