package ai.chagok.app.core

import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import ai.chagok.app.model.VehicleInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 업무용승용차 관련비용 명세서용 연간 집계 (법인세법 시행령 제50조의2 기준 단순화).
 * - 운행기록 작성: 업무사용비율 = 업무용 사용거리 / 총 주행거리
 * - 운행기록 미작성: 관련비용 1,500만원까지만 업무사용 인정
 * 감가상각비 800만원 한도, 업무전용 자동차보험 요건 등은 화면에서 안내만 한다.
 */
data class TaxSummary(
    val year: Int,
    val totalKm: Double,
    val commuteKm: Double,
    val businessKm: Double,
    val unclassifiedTrips: Int,
    val tripCount: Int,
    val totalExpense: Long,
    val byCategory: Map<ExpenseCategory, Long>,
    val pendingExpenses: Int,
) {
    val businessUseKm: Double get() = commuteKm + businessKm
    val businessRatio: Double get() = if (totalKm > 0) businessUseKm / totalKm else 0.0

    /** 운행기록부를 근거로 인정받는 비용 */
    val deductibleWithLogbook: Long get() = Math.round(totalExpense * businessRatio)

    /** 운행기록부가 없을 때 인정되는 비용 */
    val deductibleWithoutLogbook: Long get() = minOf(totalExpense, NO_LOGBOOK_LIMIT)

    /** 기록부를 썼을 때 추가로 인정되는 금액 */
    val logbookBenefit: Long get() = (deductibleWithLogbook - deductibleWithoutLogbook).coerceAtLeast(0)

    companion object {
        const val NO_LOGBOOK_LIMIT = 15_000_000L
        const val DEPRECIATION_LIMIT = 8_000_000L
    }
}

object TaxReport {
    fun summarize(year: Int, trips: List<Trip>, expenses: List<Expense>, tz: TimeZone = KST): TaxSummary {
        val ys = trips.filter { yearOf(it.startTime, tz) == year }
        val es = expenses.filter { yearOf(it.time, tz) == year }
        return TaxSummary(
            year = year,
            totalKm = ys.sumOf { it.distanceKm },
            commuteKm = ys.filter { it.purpose == TripPurpose.COMMUTE }.sumOf { it.distanceKm },
            businessKm = ys.filter { it.purpose == TripPurpose.BUSINESS }.sumOf { it.distanceKm },
            unclassifiedTrips = ys.count { it.purpose == TripPurpose.UNCLASSIFIED },
            tripCount = ys.size,
            totalExpense = es.filter { !it.pendingAmount }.sumOf { it.amountWon },
            byCategory = es.filter { !it.pendingAmount }.groupBy { it.category }
                .mapValues { (_, v) -> v.sumOf { it.amountWon } },
            pendingExpenses = es.count { it.pendingAmount },
        )
    }

    /**
     * 국세청 「업무용승용차 운행기록부」 서식(별지 제63호) 열 구성의 CSV.
     * 엑셀에서 한글이 깨지지 않도록 UTF-8 BOM을 붙인다.
     */
    fun logbookCsv(
        year: Int,
        trips: List<Trip>,
        vehicle: VehicleInfo,
        companyName: String,
        bizNumber: String,
        department: String,
        tz: TimeZone = KST,
        /** 운전자마다 부서가 다르면 운행마다 그 사람의 부서를 쓴다 */
        departmentOf: (driver: String) -> String? = { null },
    ): String {
        val sb = StringBuilder("\uFEFF")
        sb.appendLine("업무용승용차 운행기록부")
        sb.appendLine("사업연도,$year-01-01 ~ $year-12-31")
        sb.appendLine("법인명,${csv(companyName)},사업자등록번호,${csv(bizNumber)}")
        sb.appendLine("차종,${csv(vehicle.modelName)},자동차등록번호,${csv(vehicle.plateNumber)}")
        sb.appendLine()
        sb.appendLine(LOGBOOK_COLUMNS.joinToString(","))
        for (row in logbookRows(year, trips, department, tz, departmentOf)) sb.appendLine(row.joinToString(",") { csv(it) })
        val s = summarize(year, trips, emptyList(), tz)
        sb.appendLine()
        sb.appendLine("사업연도 총주행거리(km),${km(s.totalKm)}")
        sb.appendLine("사업연도 업무용 사용거리(km),${km(s.businessUseKm)}")
        sb.appendLine("업무사용비율(%),${String.format(Locale.US, "%.1f", s.businessRatio * 100)}")
        sb.appendLine("기록 출처,차량 계기판 누적거리(Pleos Vehicle SDK Odometer) 자동 기록")
        return sb.toString()
    }

    /** 별지 제63호 운행기록부 열. CSV 와 PDF 가 같이 쓴다 */
    val LOGBOOK_COLUMNS = listOf(
        "사용일자(요일)", "부서", "성명", "주행 전 계기판의 거리(km)", "주행 후 계기판의 거리(km)", "주행거리(km)",
        "업무용 사용거리-출·퇴근용(km)", "업무용 사용거리-일반 업무용(km)", "비고",
    )

    /** 운행기록부 본문 행 (CSV·PDF 공용) */
    fun logbookRows(
        year: Int,
        trips: List<Trip>,
        department: String,
        tz: TimeZone = KST,
        departmentOf: (driver: String) -> String? = { null },
    ): List<List<String>> {
        val day = SimpleDateFormat("yyyy-MM-dd(E)", Locale.KOREAN).apply { timeZone = tz }
        return trips.filter { yearOf(it.startTime, tz) == year }.sortedBy { it.startTime }.map { t ->
            val commute = if (t.purpose == TripPurpose.COMMUTE) t.distanceKm else 0.0
            val business = if (t.purpose == TripPurpose.BUSINESS) t.distanceKm else 0.0
            val note = listOf(
                if (t.toName.isNotEmpty()) "${t.fromName.ifEmpty { "-" }}→${t.toName}" else "",
                if (t.purpose == TripPurpose.UNCLASSIFIED) "미분류" else "",
                t.memo,
            ).filter { it.isNotEmpty() }.joinToString(" / ")
            listOf(
                day.format(t.startTime), departmentOf(t.driver) ?: department, t.driver, km(t.startOdoKm), km(t.endOdoKm),
                km(t.distanceKm), km(commute), km(business), note,
            )
        }
    }

    val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

    private fun yearOf(time: Long, tz: TimeZone): Int =
        Calendar.getInstance(tz).apply { timeInMillis = time }.get(Calendar.YEAR)

    private fun km(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
