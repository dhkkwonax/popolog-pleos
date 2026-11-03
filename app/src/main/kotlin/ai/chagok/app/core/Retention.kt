package ai.chagok.app.core

import java.util.Calendar
import java.util.Locale

/** 제출 확인 기반 보존기간 계산. 날짜는 모두 KST 의 yyyy-MM-dd 문자열이다 */
object Retention {
    const val MIN_YEARS = 5

    /** 관리자가 제출 완료를 확인한 기록 묶음. 포함된 운행·비용은 변경·삭제할 수 없다 */
    data class Submission(
        val id: Long = 0,
        val confirmedAt: Long,
        val year: Int,
        val tripIds: List<Long>,
        val expenseIds: List<Long>,
        /** 관리자가 저장한 제출 PDF (모르면 빈 값) */
        val pdfName: String = "",
        val pdfSha256: String = "",
        val retentionYears: Int = MIN_YEARS,
        /** 법정 보존 종료일. 비어 있으면 미확인이며, 의무가 없다는 뜻이 아니다 */
        val statutoryEnd: String = "",
    ) {
        val endDay: String get() = endDay(confirmedAt, retentionYears, statutoryEnd)
    }

    /** 확인일 + 보존기간과 법정 종료일 중 늦은 날 */
    fun endDay(confirmedAt: Long, years: Int, statutoryEnd: String): String {
        val product = addYears(Integrity.dayOf(confirmedAt), years)
        return if (statutoryEnd.isNotEmpty() && statutoryEnd > product) statutoryEnd else product
    }

    /** 2월 29일에 더한 해가 평년이면 2월 28일 */
    fun addYears(day: String, years: Int): String {
        val (y, m, d) = day.split('-').map { it.toInt() }
        val c = Calendar.getInstance().apply { clear(); set(y + years, m - 1, 1) }
        val last = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", y + years, m, minOf(d, last))
    }

    /** 만료일 당일까지 보존하므로 다음 날부터 만료 */
    fun expired(s: Submission, now: Long): Boolean = Integrity.dayOf(now) > s.endDay

    /** 입력한 법정 종료일이 실제 날짜인지. 비어 있는 값은 미확인으로 허용한다 */
    fun validDay(day: String): Boolean {
        if (day.isEmpty()) return true
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(day)) return false
        val (y, m, d) = day.split('-').map { it.toInt() }
        if (y < 2000 || y > 2100 || m !in 1..12) return false
        val c = Calendar.getInstance().apply { clear(); set(y, m - 1, 1) }
        return d in 1..c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
}
