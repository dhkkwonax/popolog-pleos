package ai.chagok.app.core

import ai.chagok.app.model.ExpenseCategory
import java.util.Calendar
import java.util.TimeZone

/**
 * Gleo(음성 비서)가 앱 함수를 부를 때 넘기는 파라미터. schema.json(assets)과 이름·값이 같아야 한다.
 * Gleo 가 채우는 값이라 모양이 조금 달라도 받아 주고, 못 읽는 값은 null 로 둔다(확인 카드에서 터치로 채움).
 */
object GleoParams {
    const val ADD_EXPENSE = "ADD_EXPENSE"
    const val QUERY_REPORT = "QUERY_REPORT"

    data class AddExpense(val category: ExpenseCategory?, val amountWon: Long?, val place: String)

    enum class Period { TODAY, THIS_WEEK, THIS_MONTH, LAST_MONTH, THIS_YEAR }

    enum class QueryAction { SHOW, EXPORT_PDF, EXPORT_CSV }

    data class Query(val category: ExpenseCategory?, val period: Period, val action: QueryAction)

    fun addExpense(p: Map<String, Any?>): AddExpense = AddExpense(
        category = category(p["category"]?.toString()),
        amountWon = amount(p["amount_won"] ?: p["amount"]),
        place = (p["place"] ?: p["shop"])?.toString()?.trim().orEmpty(),
    )

    fun query(p: Map<String, Any?>): Query = Query(
        category = category(p["category"]?.toString()),
        period = period(p["period"]?.toString()),
        action = when (p["action"]?.toString()?.trim()?.lowercase()) {
            "export_pdf", "pdf" -> QueryAction.EXPORT_PDF
            "export_csv", "csv" -> QueryAction.EXPORT_CSV
            else -> QueryAction.SHOW
        },
    )

    /** "maintenance"·"정비"·"repair" 등을 항목으로. 전체/모름이면 null */
    fun category(raw: String?): ExpenseCategory? {
        val s = raw?.trim()?.lowercase() ?: return null
        return when {
            s.isEmpty() || s == "all" || s == "전체" -> null
            listOf("maint", "repair", "정비", "수리", "수선", "오일", "타이어", "브레이크").any { it in s } -> ExpenseCategory.MAINTENANCE
            listOf("toll", "통행", "하이패스").any { it in s } -> ExpenseCategory.TOLL
            listOf("park", "주차").any { it in s } -> ExpenseCategory.PARKING
            listOf("wash", "세차").any { it in s } -> ExpenseCategory.WASH
            listOf("charge", "충전").any { it in s } -> ExpenseCategory.CHARGE
            listOf("fuel", "gas", "주유", "기름").any { it in s } -> ExpenseCategory.FUEL
            listOf("insur", "보험").any { it in s } -> ExpenseCategory.INSURANCE
            listOf("other", "기타").any { it in s } -> ExpenseCategory.OTHER
            else -> null
        }
    }

    /** 숫자(120000, 120000.0)나 숫자 문자열("120,000", "12만원")을 원 단위로 */
    fun amount(raw: Any?): Long? = when (raw) {
        null -> null
        is Number -> raw.toLong().takeIf { it > 0 }
        else -> {
            val t = raw.toString().replace(",", "").trim()
            (t.toLongOrNull() ?: t.toDoubleOrNull()?.toLong() ?: KoreanAmountParser.parse(t))?.takeIf { it > 0 }
        }
    }

    fun period(raw: String?): Period = when (raw?.trim()?.lowercase()) {
        "today", "오늘" -> Period.TODAY
        "this_week", "week", "이번 주", "이번주" -> Period.THIS_WEEK
        "last_month", "지난달", "지난 달" -> Period.LAST_MONTH
        "this_year", "year", "올해" -> Period.THIS_YEAR
        else -> Period.THIS_MONTH
    }

    /** 기간의 [시작, 끝) (KST). 주는 월요일부터 */
    fun range(period: Period, now: Long, tz: TimeZone = TaxReport.KST): LongRange {
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        fun startOfDay() = c.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return when (period) {
            Period.TODAY -> {
                val s = startOfDay().timeInMillis
                c.add(Calendar.DAY_OF_MONTH, 1); s until c.timeInMillis
            }
            Period.THIS_WEEK -> {
                startOfDay()
                val back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
                c.add(Calendar.DAY_OF_MONTH, -back)
                val s = c.timeInMillis
                c.add(Calendar.DAY_OF_MONTH, 7); s until c.timeInMillis
            }
            Period.THIS_MONTH, Period.LAST_MONTH -> {
                startOfDay(); c.set(Calendar.DAY_OF_MONTH, 1)
                if (period == Period.LAST_MONTH) c.add(Calendar.MONTH, -1)
                val s = c.timeInMillis
                c.add(Calendar.MONTH, 1); s until c.timeInMillis
            }
            Period.THIS_YEAR -> {
                startOfDay(); c.set(Calendar.DAY_OF_YEAR, 1)
                val s = c.timeInMillis
                c.add(Calendar.YEAR, 1); s until c.timeInMillis
            }
        }
    }

    fun label(p: Period) = when (p) {
        Period.TODAY -> "오늘"
        Period.THIS_WEEK -> "이번 주"
        Period.THIS_MONTH -> "이번 달"
        Period.LAST_MONTH -> "지난달"
        Period.THIS_YEAR -> "올해"
    }
}
