package ai.chagok.app.core

import ai.chagok.app.core.Integrity.Kind
import ai.chagok.app.model.ClassifiedBy
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionTest {
    // 2026-11-02 12:00 KST
    private val confirmed = 1_793_545_200_000L

    private fun sub(years: Int, statutory: String = "") =
        Retention.Submission(confirmedAt = confirmed, year = 2026, tripIds = listOf(1), expenseIds = emptyList(), retentionYears = years, statutoryEnd = statutory)

    @Test
    fun productEndWhenStatutoryEarlierOrUnknown() {
        assertEquals("2031-11-02", sub(5).endDay)
        assertEquals("2031-11-02", sub(5, "2031-06-30").endDay)
    }

    @Test
    fun statutoryEndWinsWhenLater() {
        assertEquals("2032-03-31", sub(5, "2032-03-31").endDay)
        assertEquals("2033-11-02", sub(7, "2032-03-31").endDay)
    }

    @Test
    fun leapDayAndExpiryBoundary() {
        assertEquals("2029-02-28", Retention.addYears("2024-02-29", 5))
        assertEquals("2028-02-29", Retention.addYears("2024-02-29", 4))
        val end = Retention.endDay(confirmed, 5, "")
        val lastDay = 1_932_000_000_000L // 2031-11-02 이후 날짜가 아님을 아래에서 계산
        assertFalse(Retention.expired(sub(5), confirmed))
        val onEnd = java.util.Calendar.getInstance(TaxReport.KST).apply { clear(); set(2031, 10, 2, 23, 0) }.timeInMillis
        val after = java.util.Calendar.getInstance(TaxReport.KST).apply { clear(); set(2031, 10, 3, 0, 5) }.timeInMillis
        assertFalse(Retention.expired(sub(5), onEnd))
        assertTrue(Retention.expired(sub(5), after))
        assertEquals("2031-11-02", end)
        assertTrue(lastDay > 0)
    }

    @Test
    fun validDay() {
        assertTrue(Retention.validDay(""))
        assertTrue(Retention.validDay("2032-02-29"))
        assertFalse(Retention.validDay("2031-02-29"))
        assertFalse(Retention.validDay("2031-13-01"))
        assertFalse(Retention.validDay("20310101"))
    }

    private fun trip(id: Long, i: Int) = Trip(
        id = id, startTime = 1_792_972_800_000L + i * 3_600_000L, endTime = 1_792_972_800_000L + i * 3_600_000L + 1_800_000L,
        startOdoKm = 8000.0 + i * 20, endOdoKm = 8018.4 + i * 20, startLat = 37.0, startLng = 127.0, endLat = 37.1, endLng = 127.1,
        purpose = TripPurpose.BUSINESS, classifiedBy = ClassifiedBy.PLACE, fromName = "집", toName = "한빛물산", memo = "비밀 메모", driver = "김도윤",
    )

    @Test
    fun purgedLineKeepsChainButIsReportedNotTamper() {
        val trips = listOf(trip(1, 1), trip(2, 2))
        val chain = Integrity.build(trips, emptyList())
        // 운행 2 삭제: payload 치환(hash 유지) + TRIP_DEL 덧붙임
        val purged = chain.map { if (it.ref == 2L) it.copy(payload = Integrity.PURGED + 2) else it }
        val del = Integrity.append(purged, 1_792_980_000_000L, Kind.TRIP_DEL, 2, "deleted|2")
        val after = purged + del
        val r = Integrity.verify(after, emptyList(), listOf(trips[0]), emptyList())
        assertTrue(r.ok, r.reason)
        assertEquals(listOf(2L), r.purgedSeqs)
        assertFalse(after.filter { it.ref == 2L }.any { it.payload.contains("비밀 메모") || it.payload.contains("한빛물산") })
        // 파기하지 않고 내용만 바꾸면 여전히 변조
        val forged = chain.map { if (it.ref == 1L) it.copy(payload = it.payload + "x") else it }
        assertFalse(Integrity.verify(forged, emptyList(), trips, emptyList()).ok)
    }
}
