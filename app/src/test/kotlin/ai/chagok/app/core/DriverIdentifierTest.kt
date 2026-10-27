package ai.chagok.app.core

import ai.chagok.app.model.Driver
import ai.chagok.app.model.DriverMethod
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DriverIdentifierTest {
    private val kim = Driver("kim", "김차곡", "영업팀", seatPosition = 42)
    private val park = Driver("park", "박하나", "영업지원팀", seatPosition = 18)
    private val lee = Driver("lee", "이미등", "총무팀", seatPosition = null)
    private val drivers = listOf(kim, park, lee)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(TaxReport.KST).apply {
        clear(); set(2026, Calendar.OCTOBER, day, hour, minute)
    }.timeInMillis

    private fun trip(driver: Driver, from: String, day: Int, hour: Int) = Trip(
        startTime = at(day, hour), endTime = at(day, hour) + 1_800_000L,
        startOdoKm = 0.0, endOdoKm = 10.0, startLat = 0.0, startLng = 0.0, endLat = 0.0, endLng = 0.0,
        purpose = TripPurpose.COMMUTE, fromName = from, driver = driver.name,
    )

    /** 김차곡은 아침에 집에서, 박하나는 낮에 회사에서 출발한다 */
    private val history = listOf(
        trip(kim, "집", 20, 8), trip(kim, "집", 21, 8), trip(kim, "집", 22, 7),
        trip(park, "회사", 20, 13), trip(park, "회사", 22, 14),
    )

    @Test
    fun seatWithinToleranceWins() {
        val r = DriverIdentifier.identify(drivers, seatPosition = 20, at(26, 8), "집", history, lastDriverId = "kim")!!
        assertEquals(park, r.driver)
        assertEquals(DriverMethod.SEAT, r.method)
    }

    @Test
    fun seatOutsideToleranceFallsBackToRoutine() {
        // 시트 30 은 누구와도 6 넘게 차이 난다 → 아침 8시에 집에서 출발했으니 김차곡
        val r = DriverIdentifier.identify(drivers, seatPosition = 30, at(26, 8, 20), "집", history, lastDriverId = "park")!!
        assertEquals(kim, r.driver)
        assertEquals(DriverMethod.ROUTINE, r.method)
    }

    @Test
    fun routineUsesOriginAndHour() {
        val r = DriverIdentifier.identify(drivers, seatPosition = null, at(26, 13, 30), "회사", history, lastDriverId = "kim")!!
        assertEquals(park, r.driver)
        assertEquals(DriverMethod.ROUTINE, r.method)
    }

    @Test
    fun unknownRoutineFallsBackToLastDriver() {
        // 밤 11시 쇼핑몰 출발은 기록에 없다
        val r = DriverIdentifier.identify(drivers, seatPosition = null, at(26, 23), "쇼핑몰", history, lastDriverId = "park")!!
        assertEquals(park, r.driver)
        assertEquals(DriverMethod.LAST, r.method)
    }

    @Test
    fun noLastDriverUsesFirstDriver() {
        val r = DriverIdentifier.identify(drivers, seatPosition = null, at(26, 23), "", emptyList(), lastDriverId = null)!!
        assertEquals(kim, r.driver)
        assertEquals(DriverMethod.LAST, r.method)
    }

    @Test
    fun equallyNearSeatsAreNotDecided() {
        val a = Driver("a", "가", "", seatPosition = 20)
        val b = Driver("b", "나", "", seatPosition = 24)
        assertNull(DriverIdentifier.bySeat(listOf(a, b), 22))
    }

    @Test
    fun tiedRoutineIsNotDecided() {
        val h = listOf(trip(kim, "회사", 20, 9), trip(park, "회사", 21, 9))
        assertNull(DriverIdentifier.byRoutine(drivers, at(26, 9), "회사", h, TaxReport.KST))
    }

    @Test
    fun midnightHoursAreAdjacent() {
        val h = listOf(trip(kim, "집", 20, 23))
        assertEquals(kim, DriverIdentifier.byRoutine(drivers, at(26, 0, 10), "집", h, TaxReport.KST))
    }

    @Test
    fun noDriversGivesNull() {
        assertNull(DriverIdentifier.identify(emptyList(), 20, at(26, 8), "집", history, "kim"))
    }
}
