package ai.chagok.app.core

import ai.chagok.app.model.Place
import ai.chagok.app.model.PlaceKind
import ai.chagok.app.model.Powertrain
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import ai.chagok.app.model.VehicleInfo
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoreanAmountParserTest {
    @Test
    fun parsesSpokenAmounts() {
        assertEquals(53_000, KoreanAmountParser.parse("오만 삼천원"))
        assertEquals(53_000, KoreanAmountParser.parse("5만3천 원"))
        assertEquals(53_000, KoreanAmountParser.parse("53,000원"))
        assertEquals(70_000, KoreanAmountParser.parse("주유 칠만 원 했어"))
        assertEquals(15_000, KoreanAmountParser.parse("만오천원"))
        assertEquals(10_000, KoreanAmountParser.parse("만원"))
        assertEquals(87_500, KoreanAmountParser.parse("팔만 칠천 오백 원"))
        assertEquals(120_000, KoreanAmountParser.parse("십이만 원"))
        assertEquals(1_250_000, KoreanAmountParser.parse("백이십오만원"))
        assertEquals(64_380, KoreanAmountParser.parse("이번 주유 육만 사천 삼백 팔십 원"))
    }

    @Test
    fun ignoresSentencesWithoutAmount() {
        assertNull(KoreanAmountParser.parse("이번에는 잘 모르겠어"))
        assertNull(KoreanAmountParser.parse(""))
    }
}

class TripClassifierTest {
    private val home = Place(1, "집", PlaceKind.HOME, 37.3670, 127.1080, 250)
    private val office = Place(2, "회사", PlaceKind.OFFICE, 37.5006, 127.0364, 250)
    private val client = Place(3, "한빛물산", PlaceKind.CLIENT, 37.4022, 127.1105, 300)
    private val places = listOf(home, office, client)

    @Test
    fun arrivalAtClientIsBusiness() {
        val c = TripClassifier.classify(office.lat, office.lng, 37.4030, 127.1110, places)
        assertEquals(TripPurpose.BUSINESS, c.purpose)
        assertEquals(client, c.toPlace)
    }

    @Test
    fun clientBackToOfficeIsBusiness() {
        assertEquals(TripPurpose.BUSINESS, TripClassifier.classify(client.lat, client.lng, office.lat, office.lng, places).purpose)
    }

    @Test
    fun homeOfficeIsCommute() {
        assertEquals(TripPurpose.COMMUTE, TripClassifier.classify(home.lat, home.lng, office.lat, office.lng, places).purpose)
        assertEquals(TripPurpose.COMMUTE, TripClassifier.classify(office.lat, office.lng, home.lat, home.lng, places).purpose)
    }

    @Test
    fun unknownPlaceIsUnclassified() {
        assertEquals(TripPurpose.UNCLASSIFIED, TripClassifier.classify(office.lat, office.lng, 37.5453, 127.2238, places).purpose)
    }

    @Test
    fun justOutsideRadiusDoesNotMatch() {
        // 한빛물산에서 북쪽으로 약 400m
        assertNull(Geo.nearestPlace(client.lat + 0.0036, client.lng, places))
    }
}

class DetectorTest {
    @Test
    fun tripStartsOnOdometerAndEndsOnParkingBrake() {
        val ends = mutableListOf<TripEnd>()
        val r = TripRecorder(object : TripRecorder.Listener {
            override fun onTripStarted(start: TripStart) = Unit
            override fun onTripEnded(end: TripEnd) { ends += end }
        })
        r.onLocation(1.0, 2.0)
        r.onOdometer(1000.0, 0)
        r.onParkingBrake(false, 1)
        r.onOdometer(1000.05, 2)
        assertTrue(!r.isDriving)
        r.onLocation(1.001, 2.0)
        r.onOdometer(1000.2, 3)
        assertTrue(r.isDriving)
        assertEquals(1.0, r.currentTrip!!.lat, "출발지는 주차 위치")
        r.onOdometer(1024.6, 4)
        r.onLocation(3.0, 4.0)
        r.onParkingBrake(true, 5)
        assertEquals(1, ends.size)
        assertEquals(1000.0, ends[0].start.odoKm)
        assertEquals(1024.6, ends[0].odoKm)
        assertEquals(3.0, ends[0].lat)
    }

    @Test
    fun tripEndsAfterIdleTimeout() {
        var ended = 0
        val r = TripRecorder(object : TripRecorder.Listener {
            override fun onTripStarted(start: TripStart) = Unit
            override fun onTripEnded(end: TripEnd) { ended++ }
        }, idleTimeoutMs = 1000)
        r.onOdometer(10.0, 0)
        r.onOdometer(11.0, 100)
        r.tick(900)
        assertEquals(0, ended)
        r.tick(1200)
        assertEquals(1, ended)
    }

    @Test
    fun refuelDetectedWhileParked() {
        val events = mutableListOf<EnergyEvent>()
        val d = EnergyEventDetector(5.0, settleMs = 1000) { events += it }
        d.onLevel(40.0, 0, driving = true)
        d.onLevel(27.0, 10, driving = true)
        d.onLevel(27.0, 20, driving = false)
        d.onLevel(50.0, 30, driving = false)
        d.onLevel(100.0, 40, driving = false)
        d.tick(500)
        assertEquals(0, events.size)
        d.tick(1100)
        assertEquals(1, events.size)
        assertEquals(27.0, events[0].fromPercent)
        assertEquals(100.0, events[0].toPercent)
    }

    @Test
    fun smallFluctuationIsIgnored() {
        val events = mutableListOf<EnergyEvent>()
        val d = EnergyEventDetector(5.0, settleMs = 1000) { events += it }
        d.onLevel(30.0, 0, driving = false)
        d.onLevel(32.0, 10, driving = false)
        d.tick(2000)
        d.onLevel(31.0, 2100, driving = true)
        assertEquals(0, events.size)
    }
}

class TaxReportTest {
    private fun t(day: Int, km: Double, p: TripPurpose, odo: Double): Trip {
        val time = Calendar.getInstance(TaxReport.KST).apply { clear(); set(2026, Calendar.JUNE, day, 9, 0) }.timeInMillis
        return Trip(startTime = time, endTime = time + 3_600_000, startOdoKm = odo, endOdoKm = odo + km,
            startLat = 0.0, startLng = 0.0, endLat = 0.0, endLng = 0.0, purpose = p, toName = "x")
    }

    @Test
    fun ratioAndDeduction() {
        val trips = listOf(
            t(1, 25.0, TripPurpose.COMMUTE, 0.0),
            t(2, 35.0, TripPurpose.BUSINESS, 25.0),
            t(3, 40.0, TripPurpose.PERSONAL, 60.0),
        )
        val june = trips[0].startTime
        val expenses = listOf(
            Expense(time = june, category = ExpenseCategory.LEASE, amountWon = 20_000_000),
            Expense(time = june, category = ExpenseCategory.FUEL, amountWon = 0, pendingAmount = true),
        )
        val s = TaxReport.summarize(2026, trips, expenses)
        assertEquals(100.0, s.totalKm, 1e-9)
        assertEquals(0.6, s.businessRatio, 1e-9)
        assertEquals(12_000_000, s.deductibleWithLogbook)
        assertEquals(15_000_000, s.deductibleWithoutLogbook)
        assertEquals(1, s.pendingExpenses)
    }

    @Test
    fun logbookCsvHasNtsColumns() {
        val trips = listOf(t(1, 25.0, TripPurpose.COMMUTE, 100.0), t(2, 10.0, TripPurpose.PERSONAL, 125.0))
        val csv = TaxReport.logbookCsv(2026, trips, VehicleInfo("그랜저", "123가 4567", "VIN", Powertrain.ICE, 64.0), "한결상사", "123-45-67890", "영업팀")
        assertTrue(csv.startsWith("﻿"))
        assertTrue("주행 전 계기판의 거리(km)" in csv)
        assertTrue("2026-06-01(월),영업팀,,100.0,125.0,25.0,25.0,0.0" in csv, csv)
        assertTrue("업무사용비율(%),71.4" in csv)
    }
}
