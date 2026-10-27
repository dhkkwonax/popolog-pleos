package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.TaxReport
import ai.chagok.app.core.TaxSummary
import ai.chagok.app.model.ExpenseCategory
import android.view.Gravity
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 세무 모드: 업무용승용차 관련비용 명세서·운행기록부 준비 */
class TaxScreen(private val a: MainActivity) {
    private val c = a.controller

    fun render(): View = a.vbox {
        val now = c.gateway.now()
        val cal = Calendar.getInstance(TaxReport.KST).apply { timeInMillis = now }
        val year = cal.get(Calendar.YEAR)
        val s = TaxReport.summarize(year, c.trips, c.expenses)

        val heading = a.vbox {
            add(a.text("${year}년 업무용승용차 리포트", 24f, bold = true))
            add(a.text("법인·개인사업자 업무용 차량 비용 처리용 · 운행기록부 자동 작성", 15f, C.SUB), top = 6)
        }
        val exportLog = a.button("운행기록부 CSV", BtnStyle.PRIMARY) { exportLogbook(year) }
        val exportCost = a.button("비용 명세 CSV") { exportExpenses(year) }
        if (a.isNarrow) {
            // 좁은 창에서는 버튼이 제목을 "리/포트" 로 꺾으므로 제목 아래 줄로 내린다
            add(heading)
            add(a.grid(2, listOf(exportLog, exportCost), gapDp = 10), top = 14)
        } else {
            add(a.hbox {
                add(heading, 0, WRAP, 1f)
                add(exportLog, WRAP, WRAP, end = 10)
                add(exportCost, WRAP, WRAP)
            })
        }

        add(a.pair(ratioCard(s), deductCard(s, cal), secondWeight = 1.3f), top = 16)
        add(a.pair(costCard(s), checklist(s), secondWeight = 1.3f), top = 12)

        add(
            a.text(
                "참고용 추정치입니다. 감가상각비(연 800만원 한도)·임차료 중 감가상각비 상당액 구분, 업종별 특례는 반영하지 않았습니다. 신고 전 세무 전문가와 확인하세요.",
                14f, C.DIM,
            ),
            top = 16,
        )
    }

    private fun ratioCard(s: TaxSummary): View = a.card {
        add(a.text("업무사용비율", 15f, C.SUB))
        // 화면의 답 숫자. 운전 중 한눈에 읽히게 크게 (큰 화면 48sp), 색은 아래 막대(데이터)에만
        add(a.text(String.format(Locale.US, "%.1f%%", s.businessRatio * 100), 38f, bold = true, tabular = true), top = 8)
        add(a.bar(s.businessRatio, C.ACCENT, 14), top = 14)
        add(a.text("업무용 사용거리 ÷ 총 주행거리", 14f, C.DIM), top = 10)
        add(a.divider(), top = 16)
        fun line(k: String, v: String, color: Int = C.TEXT) = a.hbox {
            add(a.text(k, 16f, C.SUB), 0, WRAP, 1f)
            add(a.text(v, 17f, color, bold = true, tabular = true), WRAP, WRAP)
        }
        add(line("총 주행거리", AppController.fmtKm(s.totalKm)), top = 14)
        add(line("· 일반 업무용", AppController.fmtKm(s.businessKm)), top = 10)
        add(line("· 출·퇴근용", AppController.fmtKm(s.commuteKm)), top = 10)
        add(line("· 비업무", AppController.fmtKm(s.totalKm - s.businessUseKm - unclassifiedKm(s))), top = 10)
        if (s.unclassifiedTrips > 0) add(line("· 미분류 (비업무로 계산)", "${s.unclassifiedTrips}건", C.AMBER), top = 10)
        addFiller()
        add(link("운행일지에서 운행별로 보기") { a.select(MainActivity.Tab.LOGBOOK) }, WRAP, WRAP)
    }

    /** 늘어난 카드 아래 끝에 붙이는 글자 링크 */
    private fun link(label: String, onClick: () -> Unit): View = a.text(label, 16f, C.SUB).apply {
        setCompoundDrawablesRelative(null, null, a.iconDrawable(Ic.CHEVRON_RIGHT, C.SUB, 18), null)
        compoundDrawablePadding = a.dp(4)
        setPadding(0, a.dp(16), 0, 0)
        setOnClickListener { onClick() }
    }

    private fun unclassifiedKm(s: TaxSummary): Double =
        c.trips.filter { it.purpose == ai.chagok.app.model.TripPurpose.UNCLASSIFIED && yearOf(it.startTime) == s.year }.sumOf { it.distanceKm }

    private fun yearOf(t: Long) = Calendar.getInstance(TaxReport.KST).apply { timeInMillis = t }.get(Calendar.YEAR)

    private fun deductCard(s: TaxSummary, cal: Calendar): View = a.card {
        add(a.text("비용 인정액 비교 (누적)", 15f, C.SUB))
        add(a.text(AppController.won(s.totalExpense), 24f, bold = true, tabular = true), top = 8)
        add(a.text("차량 관련비용 합계", 14f, C.DIM), top = 6)
        add(a.divider(), top = 16)

        fun compare(label: String, value: Long, sub: String, color: Int) = a.vbox {
            add(a.hbox {
                add(a.text(label, 16f, C.SUB), 0, WRAP, 1f)
                add(a.text(AppController.won(value), 20f, color, bold = true, tabular = true), WRAP, WRAP)
            })
            add(a.bar(value.toDouble() / s.totalExpense.coerceAtLeast(1), color, 10), top = 8)
            add(a.text(sub, 13f, C.DIM), top = 6)
        }
        // 막대는 데이터라 운행기록부 쪽만 강조색, 비교 대상은 회색. 금액 글자는 둘 다 잉크
        add(compare("운행기록부 작성 시", s.deductibleWithLogbook, "관련비용 × 업무사용비율", C.ACCENT), top = 14)
        add(compare("기록부 없을 때", s.deductibleWithoutLogbook, "연 1,500만원 한도", C.DIM), top = 14)

        // 연말까지 같은 패턴이 이어진다고 보고 연간 환산
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val days = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
        val firstUse = c.expenses.filter { yearOf(it.time) == s.year }.minOfOrNull { it.time }
        val startDay = firstUse?.let { Calendar.getInstance(TaxReport.KST).apply { timeInMillis = it }.get(Calendar.DAY_OF_YEAR) } ?: 1
        val elapsed = (dayOfYear - startDay + 1).coerceAtLeast(1)
        val projected = Math.round(s.totalExpense * (days - startDay + 1).toDouble() / elapsed)
        val projWith = Math.round(projected * s.businessRatio)
        val projWithout = minOf(projected, TaxSummary.NO_LOGBOOK_LIMIT)
        add(a.divider(), top = 16)
        add(a.text("연말 예상 (현재 추세 유지 시)", 15f, C.SUB), top = 14)
        add(a.text("관련비용 ${AppController.won(projected)}", 18f, bold = true), top = 8)
        val gain = projWith - projWithout
        add(
            a.text(
                if (gain > 0) "운행기록부로 ${AppController.won(gain)} 더 비용 인정" else "1,500만원 이하라 기록부 없이도 전액 인정 범위 · 세무조사 대비 근거로 보관",
                16f, if (gain > 0) C.TEXT else C.SUB, bold = gain > 0,
            ),
            top = 6,
        )
    }

    private fun costCard(s: TaxSummary): View = a.card {
        add(a.text("관련비용 항목", 15f, C.SUB))
        val order = s.byCategory.entries.sortedByDescending { it.value }
        if (order.isEmpty()) add(a.text("기록 없음", 16f, C.DIM), top = 10)
        for ((cat, v) in order) {
            add(a.hbox {
                add(a.icon(cat.ic, C.SUB, 18), WRAP, WRAP, end = 10)
                add(a.text(cat.label, 16f), 0, WRAP, 1f)
                add(a.text(AppController.won(v), 17f, bold = true, tabular = true), WRAP, WRAP)
            }, top = 12)
        }
        // 옆 체크리스트와 아래 끝을 맞추고, 남는 높이 아래에 합계를 붙인다
        addFiller()
        add(a.divider(), top = 16)
        add(a.hbox {
            add(a.text("합계", 16f, C.SUB), 0, WRAP, 1f)
            add(a.text(AppController.won(s.totalExpense), 18f, bold = true, tabular = true), WRAP, WRAP)
        }, top = 12)
    }

    private fun checklist(s: TaxSummary): View = a.card {
        add(a.text("신고 준비 체크", 15f, C.SUB))
        val insured = s.byCategory.containsKey(ExpenseCategory.INSURANCE)
        fun item(ok: Boolean?, title: String, sub: String) = a.hbox(Gravity.TOP) {
            // 둥근 색 바탕 없이 외곽선 아이콘만. 손봐야 할 것만 색
            val (ic, color) = when (ok) {
                true -> Ic.CHECK to C.SUB
                false -> Ic.ALERT to C.AMBER
                null -> Ic.INFO to C.SUB
            }
            add(a.icon(ic, color, 22), WRAP, WRAP, end = 14)
            add(a.vbox {
                add(a.text(title, 17f, bold = true))
                add(a.text(sub, 14f, C.DIM), top = 4)
            }, 0, WRAP, 1f)
        }
        add(item(true, "운행기록부 자동 작성 중", "${s.tripCount}회 운행 · 계기판 값으로 누락 없이 기록"), top = 14)
        add(item(s.unclassifiedTrips == 0, "운행 목적 분류", if (s.unclassifiedTrips == 0) "모든 운행 분류 완료" else "미분류 ${s.unclassifiedTrips}건 → 운행일지에서 지정"), top = 14)
        add(item(s.pendingExpenses == 0, "주유·충전 금액", if (s.pendingExpenses == 0) "금액 누락 없음" else "금액 미입력 ${s.pendingExpenses}건"), top = 14)
        add(item(insured, "업무전용 자동차보험", if (insured) "보험료 기록 확인 (법인은 가입 필수)" else "보험료 기록 없음 · 법인은 미가입 시 비용 불인정"), top = 14)
        add(item(null, "감가상각비 한도", "업무사용분 연 800만원 초과분은 다음 해로 이월"), top = 14)
    }

    private fun exportLogbook(year: Int) {
        val csv = TaxReport.logbookCsv(
            year, c.trips, c.gateway.info,
            companyName = c.db.setting(AppController.KEY_COMPANY),
            bizNumber = c.db.setting(AppController.KEY_BIZNO),
            department = c.db.setting(AppController.KEY_DEPT),
            departmentOf = { name -> c.drivers.firstOrNull { it.name == name }?.department },
        )
        a.saveCsv("운행기록부_${year}_${stamp()}.csv", csv)
    }

    private fun exportExpenses(year: Int) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREAN).apply { timeZone = TaxReport.KST }
        val sb = StringBuilder("﻿일시,항목,금액(원),수량,계기판(km),입력 방식,메모\n")
        c.expenses.filter { yearOf(it.time) == year && !it.pendingAmount }.sortedBy { it.time }.forEach { e ->
            val unit = if (e.category == ExpenseCategory.CHARGE) "kWh" else if (e.category == ExpenseCategory.FUEL) "L" else ""
            sb.append(
                listOf(
                    fmt.format(e.time), e.category.label, e.amountWon.toString(),
                    e.quantity?.let { AppController.fmt1(it) + unit } ?: "", e.odoKm?.let { AppController.fmt1(it) } ?: "",
                    e.source.label, "\"" + e.memo.replace("\"", "\"\"") + "\"",
                ).joinToString(","),
            ).append('\n')
        }
        a.saveCsv("차량비용_${year}_${stamp()}.csv", sb.toString())
    }

    private fun stamp() = SimpleDateFormat("MMdd_HHmm", Locale.US).apply { timeZone = TaxReport.KST }.format(System.currentTimeMillis())
}
