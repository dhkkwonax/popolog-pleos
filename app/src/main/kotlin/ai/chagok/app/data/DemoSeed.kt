package ai.chagok.app.data

import ai.chagok.app.core.TaxReport
import ai.chagok.app.model.ClassifiedBy
import ai.chagok.app.model.Driver
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.ExpenseSource
import ai.chagok.app.model.Place
import ai.chagok.app.model.PlaceKind
import ai.chagok.app.model.Powertrain
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import ai.chagok.app.model.VehicleInfo
import java.util.Calendar
import java.util.Random

/**
 * 시연용 데이터: 2026년 6월 신차 출고 후 오늘까지 출퇴근·거래처 방문·주말 개인 운행과 비용.
 * 매번 같은 결과가 나오도록 고정 시드를 쓴다.
 */
object DemoSeed {
    val HOME = Place(name = "집 (분당 정자동)", kind = PlaceKind.HOME, lat = 37.3670, lng = 127.1080, radiusM = 250, address = "경기 성남시 분당구 정자동")
    val OFFICE = Place(name = "회사 (역삼)", kind = PlaceKind.OFFICE, lat = 37.5006, lng = 127.0364, radiusM = 250, address = "서울 강남구 테헤란로")
    val CLIENT_A = Place(name = "한빛물산 (판교)", kind = PlaceKind.CLIENT, lat = 37.4022, lng = 127.1105, radiusM = 300, address = "경기 성남시 분당구 판교역로")
    val CLIENT_B = Place(name = "대성정밀 (수원)", kind = PlaceKind.CLIENT, lat = 37.2574, lng = 127.0313, radiusM = 300, address = "경기 수원시 영통구 광교로")

    /** 등록되지 않은 장소 (개인 운행·미분류 시연용) */
    const val MALL_LAT = 37.5453
    const val MALL_LNG = 127.2238
    const val MALL_NAME = "하남 쇼핑몰"

    const val START_ODO = 12.0

    /** 이 차를 함께 쓰는 두 사람. 시트 위치가 달라 시동 때 누가 탔는지 알 수 있다 */
    val KIM = Driver("kim", "김차곡", "영업팀", seatPosition = 42)
    val PARK = Driver("park", "박하나", "영업지원팀", seatPosition = 18)

    fun vehicle(p: Powertrain) = when (p) {
        Powertrain.ICE -> VehicleInfo("그랜저 2.5 가솔린", "123가 4567", "KMHxxxxxxxxx40213", Powertrain.ICE, 64.0)
        Powertrain.EV -> VehicleInfo("아이오닉 전기차", "321나 7654", "KMHxxxxxxxxx70588", Powertrain.EV, 63.0)
    }

    fun seed(db: ChagokDb, p: Powertrain, now: Long = System.currentTimeMillis()) {
        listOf(HOME, OFFICE, CLIENT_A, CLIENT_B).forEach { db.upsert(it) }
        db.setSetting("company", "주식회사 차곡상사")
        db.setSetting("bizno", "123-45-67890")
        db.setSetting("dept", "영업팀")
        db.saveDrivers(listOf(KIM, PARK))

        val v = vehicle(p)
        val rnd = Random(20260601)
        val cal = Calendar.getInstance(TaxReport.KST).apply {
            clear(); set(2026, Calendar.JUNE, 1, 0, 0, 0)
        }
        val today = Calendar.getInstance(TaxReport.KST).apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var odo = START_ODO
        var level = 100.0
        val db2 = db.writableDatabase
        db2.beginTransaction()
        try {
            fun at(h: Int, m: Int): Long = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            }.timeInMillis

            // 출퇴근·주말은 김차곡, 낮 거래처 방문은 박하나가 몬다
            fun trip(from: Place?, to: Place?, toLat: Double, toLng: Double, km: Double, start: Long, purpose: TripPurpose, by: ClassifiedBy, toName: String, driver: Driver = KIM) {
                val dist = Math.round((km + rnd.nextGaussian() * km * 0.03) * 10) / 10.0
                val minutes = (dist / 38.0 * 60).toLong() + 5
                db.upsert(
                    Trip(
                        startTime = start, endTime = start + minutes * 60_000L,
                        startOdoKm = odo, endOdoKm = Math.round((odo + dist) * 10) / 10.0,
                        startLat = from?.lat ?: MALL_LAT, startLng = from?.lng ?: MALL_LNG, endLat = toLat, endLng = toLng,
                        purpose = purpose, classifiedBy = by, fromName = from?.name ?: MALL_NAME, toName = toName,
                        memo = when {
                            by == ClassifiedBy.PLACE && to?.kind == PlaceKind.CLIENT -> "도착지가 거래처 반경 안"
                            by == ClassifiedBy.PLACE -> "집 ↔ 회사"
                            else -> ""
                        },
                        driver = driver.name,
                    ),
                )
                odo = Math.round((odo + dist) * 10) / 10.0
                level -= when (p) {
                    Powertrain.ICE -> dist / 11.5 / v.energyCapacity * 100
                    Powertrain.EV -> dist * 0.165 / v.energyCapacity * 100
                }
                if (level < 22) {
                    val filled = 100 - level
                    val qty = filled / 100 * v.energyCapacity
                    val unit = if (p == Powertrain.ICE) 1640 + rnd.nextInt(90) else 285 + rnd.nextInt(80)
                    db.upsert(
                        Expense(
                            time = start + minutes * 60_000L + 10 * 60_000L,
                            category = if (p == Powertrain.ICE) ExpenseCategory.FUEL else ExpenseCategory.CHARGE,
                            amountWon = Math.round(qty * unit / 100.0) * 100, odoKm = odo,
                            quantity = Math.round(qty * 10) / 10.0,
                            memo = String.format(java.util.Locale.US, "%.0f%% → 100%%", level),
                            source = if (rnd.nextBoolean()) ExpenseSource.VOICE else ExpenseSource.AUTO,
                        ),
                    )
                    level = 100.0
                }
            }

            fun expense(time: Long, c: ExpenseCategory, amount: Long, memo: String, src: ExpenseSource = ExpenseSource.MANUAL) {
                db.upsert(Expense(time = time, category = c, amountWon = amount, odoKm = odo, memo = memo, source = src))
            }

            expense(at(10, 0), ExpenseCategory.INSURANCE, 1_318_000, "업무전용 자동차보험 1년")
            while (cal.before(today)) {
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                val day = cal.get(Calendar.DAY_OF_MONTH)
                if (day == 1) expense(at(9, 0), ExpenseCategory.LEASE, 1_150_000, "장기렌트 월 납입")
                if (dow in Calendar.MONDAY..Calendar.FRIDAY) {
                    trip(HOME, OFFICE, OFFICE.lat, OFFICE.lng, 24.6, at(8, 5 + rnd.nextInt(30)), TripPurpose.COMMUTE, ClassifiedBy.PLACE, OFFICE.name)
                    if (dow == Calendar.TUESDAY || dow == Calendar.THURSDAY) {
                        trip(OFFICE, CLIENT_A, CLIENT_A.lat, CLIENT_A.lng, 17.8, at(13, 40 + rnd.nextInt(15)), TripPurpose.BUSINESS, ClassifiedBy.PLACE, CLIENT_A.name, PARK)
                        if (rnd.nextInt(3) == 0) expense(at(15, 50), ExpenseCategory.PARKING, 3_000L + rnd.nextInt(3) * 1_000L, "${CLIENT_A.name} 방문 주차")
                        trip(CLIENT_A, OFFICE, OFFICE.lat, OFFICE.lng, 17.6, at(16, 10 + rnd.nextInt(20)), TripPurpose.BUSINESS, ClassifiedBy.PLACE, OFFICE.name, PARK)
                    }
                    if (dow == Calendar.WEDNESDAY && cal.get(Calendar.WEEK_OF_YEAR) % 2 == 0) {
                        trip(OFFICE, CLIENT_B, CLIENT_B.lat, CLIENT_B.lng, 36.4, at(10, 20), TripPurpose.BUSINESS, ClassifiedBy.PLACE, CLIENT_B.name, PARK)
                        expense(at(10, 50), ExpenseCategory.TOLL, 1_900, "하이패스 · ${CLIENT_B.name} 방문", ExpenseSource.AUTO)
                        trip(CLIENT_B, OFFICE, OFFICE.lat, OFFICE.lng, 36.9, at(15, 0), TripPurpose.BUSINESS, ClassifiedBy.PLACE, OFFICE.name, PARK)
                        expense(at(15, 40), ExpenseCategory.TOLL, 1_900, "하이패스 · 회사 복귀", ExpenseSource.AUTO)
                    }
                    trip(OFFICE, HOME, HOME.lat, HOME.lng, 24.9, at(18, 30 + rnd.nextInt(40)), TripPurpose.COMMUTE, ClassifiedBy.PLACE, HOME.name)
                } else if (dow == Calendar.SATURDAY && rnd.nextInt(2) == 0) {
                    trip(HOME, null, MALL_LAT, MALL_LNG, 31.2, at(11, 0), TripPurpose.PERSONAL, ClassifiedBy.VOICE, MALL_NAME)
                    trip(null, HOME, HOME.lat, HOME.lng, 31.0, at(16, 30), TripPurpose.PERSONAL, ClassifiedBy.VOICE, HOME.name)
                }
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
            expense(at(9, 0) - 30L * 86_400_000L, ExpenseCategory.MAINTENANCE, 88_000, "첫 엔진오일·필터 교환")
            expense(at(12, 0) - 6L * 86_400_000L, ExpenseCategory.WASH, 15_000, "자동 세차")
            db2.setTransactionSuccessful()
        } finally {
            db2.endTransaction()
        }
    }
}
