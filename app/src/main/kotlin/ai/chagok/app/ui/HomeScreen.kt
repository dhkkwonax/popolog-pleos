package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.TaxReport
import ai.chagok.app.model.DriverMethod
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.TripPurpose
import android.view.Gravity
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeScreen(private val a: MainActivity) {
    private val c = a.controller

    /**
     * 홈이 먼저 답하는 질문: "이번 달 차 비용이 빠짐없이 기록되고 있나?"
     * 그래서 가장 큰 숫자는 이번 달 차량비, 바로 아래 한 줄은 지금 손볼 것(발견 사항)이다.
     */
    fun render(): View = a.vbox {
        add(finding())
        c.driverGreeting?.let { add(greeting(it), top = 14) }
        c.pendingExpense()?.let { add(expensePrompt(it), top = 14) }
        c.pendingTrip()?.let { add(tripPrompt(it), top = 14) }
        add(kpis(), top = 16)
        add(recent(), top = 16)
    }

    /** 제목 아래 한 줄: 처리할 것이 있으면 그것, 없으면 방금 자동 기록된 것 */
    private fun finding(): View {
        val pendingTrips = c.trips.count { it.purpose == TripPurpose.UNCLASSIFIED }
        val pendingAmounts = c.expenses.count { it.pendingAmount }
        val todo = listOfNotNull(
            if (pendingAmounts > 0) "주유·충전 ${pendingAmounts}건 금액 입력 필요" else null,
            if (pendingTrips > 0) "운행 ${pendingTrips}건 목적 확인 필요" else null,
        )
        val (ic, color, line) = when {
            todo.isNotEmpty() -> Triple(Ic.ALERT, C.AMBER, todo.joinToString(" · "))
            c.lastNotice.isNotEmpty() -> Triple(Ic.CHECK, C.SUB, c.lastNotice)
            else -> Triple(Ic.CHECK, C.SUB, "이번 달 기록이 모두 정리됐어요")
        }
        return a.hbox {
            add(a.icon(ic, color, 20), WRAP, WRAP, end = 10)
            add(a.text(line, 18f, if (todo.isNotEmpty()) C.TEXT else C.SUB), 0, WRAP, 1f)
        }
    }

    /** 시동 직후 잠깐: 누구로 인식했는지와 바꾸는 방법 */
    private fun greeting(text: String): View = a.card(C.SURFACE, stroke = C.LINE) {
        val d = c.driver
        val how = d?.let {
            val by = if (it.method == DriverMethod.MANUAL) "직접 선택으로 바꿈" else "${it.method.label}로 인식"
            "$by · ${it.driver.department}"
        }.orEmpty()
        val change = a.button("다른 분이에요", BtnStyle.SECONDARY) { a.pickDriver() }
        add(a.hbox {
            add(a.icon(Ic.PERSON, C.SUB, 28), WRAP, WRAP, end = 16)
            add(a.vbox {
                add(a.text(text, 22f, bold = true))
                add(a.text(how, 16f, C.SUB), top = 6)
            }, 0, WRAP, 1f)
            // 좁은 창에서는 버튼이 설명을 단어 중간에서 꺾으므로 아래 줄로 내린다
            if (!a.isNarrow) add(change, WRAP, WRAP)
        })
        if (a.isNarrow) add(change, top = 14)
    }

    private fun expensePrompt(e: ai.chagok.app.model.Expense): View = a.card(C.SURFACE, stroke = C.AMBER) {
        val unit = if (e.category == ExpenseCategory.CHARGE) "kWh" else "L"
        add(a.hbox {
            add(a.icon(e.category.ic, C.AMBER, 28), WRAP, WRAP, end = 16)
            add(a.vbox {
                // "주유가" / "충전이" — 받침 유무로 조사를 고른다
                val subject = if (e.category == ExpenseCategory.CHARGE) "충전이" else "주유가"
                add(a.text("$subject 감지됐어요 · 약 ${AppController.fmt1(e.quantity ?: 0.0)}$unit", 22f, bold = true))
                val gauge = if (e.category == ExpenseCategory.CHARGE) "배터리" else "연료"
                add(a.text("$gauge ${e.memo} · 결제 금액만 말씀하시면 장부에 들어갑니다", 16f, C.SUB), top = 6)
            }, 0, WRAP, 1f)
        })
        add(a.hbox {
            add(a.button("금액 말하기", BtnStyle.PRIMARY, icon = Ic.MIC) { a.askAmountByVoice(e) }, 0, WRAP, 1.2f, end = 10)
            add(a.button("직접 입력") { a.askAmountByKeypad(e) }, 0, WRAP, 1f, end = 10)
            add(a.button("나중에", BtnStyle.GHOST) { c.dismissExpenseQuestion() }, 0, WRAP, 0.8f)
        }, top = 18)
    }

    private fun tripPrompt(t: ai.chagok.app.model.Trip): View = a.card(C.SURFACE, stroke = C.AMBER) {
        add(a.hbox {
            add(a.icon(Ic.CAR, C.AMBER, 28), WRAP, WRAP, end = 16)
            add(a.vbox {
                add(a.text("${AppController.fmtKm(t.distanceKm)} 운행 · 업무였나요?", 22f, bold = true))
                add(
                    a.text(
                        "${t.fromName.ifEmpty { "출발지" }} → ${t.toName.ifEmpty { "등록되지 않은 장소" }} · 계기판 ${AppController.fmt1(t.startOdoKm)} → ${AppController.fmt1(t.endOdoKm)} km",
                        16f, C.SUB,
                    ),
                    top = 6,
                )
            }, 0, WRAP, 1f)
        })
        add(a.hbox {
            add(a.button("말하기", BtnStyle.PRIMARY, icon = Ic.MIC) { a.askPurposeByVoice(t) }, 0, WRAP, 1f, end = 10)
            add(a.button("일반업무") { c.classifyTrip(t.id, TripPurpose.BUSINESS, ai.chagok.app.model.ClassifiedBy.MANUAL) }, 0, WRAP, 1f, end = 10)
            add(a.button("출퇴근") { c.classifyTrip(t.id, TripPurpose.COMMUTE, ai.chagok.app.model.ClassifiedBy.MANUAL) }, 0, WRAP, 1f, end = 10)
            add(a.button("비업무") { c.classifyTrip(t.id, TripPurpose.PERSONAL, ai.chagok.app.model.ClassifiedBy.MANUAL) }, 0, WRAP, 1f)
        }, top = 18)
    }

    private fun kpis(): View {
        val now = c.gateway.now()
        val cal = Calendar.getInstance(TaxReport.KST).apply { timeInMillis = now }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH)
        fun sameMonth(t: Long) = Calendar.getInstance(TaxReport.KST).apply { timeInMillis = t }.let {
            it.get(Calendar.YEAR) == y && it.get(Calendar.MONTH) == m
        }
        val monthCost = c.expenses.filter { sameMonth(it.time) && !it.pendingAmount }.sumOf { it.amountWon }
        val monthKm = c.trips.filter { sameMonth(it.startTime) }.sumOf { it.distanceKm }
        val year = TaxReport.summarize(y, c.trips, c.expenses)
        val todo = year.unclassifiedTrips + year.pendingExpenses
        val perKm = if (monthKm > 0) monthCost / monthKm else 0.0

        // 답 숫자(이번 달 차량비)는 나머지보다 확실히 크게 (큰 화면 48 · 30sp)
        val lead = kpi("${m + 1}월 차량비", AppController.won(monthCost), "km당 ${Math.round(perKm)}원 · 주행 ${AppController.fmtKm(monthKm)}", LEAD_SP) { a.select(MainActivity.Tab.LEDGER) }
        data class Fig(val label: String, val value: String, val sub: String, val color: Int, val tab: MainActivity.Tab)
        val figs = listOf(
            Fig("${m + 1}월 주행", AppController.fmtKm(monthKm), "계기판 누적거리 기준", C.TEXT, MainActivity.Tab.LOGBOOK),
            Fig("${y}년 업무사용비율", String.format(Locale.US, "%.1f%%", year.businessRatio * 100), "출퇴근 포함", C.TEXT, MainActivity.Tab.TAX),
            Fig(
                "확인할 항목", "${todo}건", if (todo == 0) "모두 정리됨" else "미분류 ${year.unclassifiedTrips} · 금액 ${year.pendingExpenses}",
                if (todo > 0) C.AMBER else C.TEXT, MainActivity.Tab.LOGBOOK,
            ),
        )
        if (a.isNarrow) {
            // 좁은 창: 답 숫자를 맨 위 전체 폭에, 나머지는 '라벨 … 값' 한 줄씩 (세 칸으로 나누면 라벨이 잘린다)
            val list = a.vbox {
                figs.forEachIndexed { i, f ->
                    if (i > 0) add(a.divider(), top = 14)
                    add(a.hbox {
                        add(a.text(f.label, 15f, C.SUB, lines = 1), 0, WRAP, 1f, end = 12)
                        add(a.text(f.value, 20f, f.color, bold = true, lines = 1, tabular = true), WRAP, WRAP)
                        setOnClickListener { a.select(f.tab) }
                    }, top = if (i > 0) 14 else 0)
                }
            }
            return a.strip(listOf(list), 1, lead)
        }
        // 넓은 창: 답 숫자 칸을 넓게(1.6), 나머지 셋을 같은 폭으로 옆에
        return a.hbox(Gravity.TOP) {
            background = a.rounded(C.SURFACE, 20)
            val pad = a.dp(22)
            fun cell(v: View) = a.vbox { setPadding(pad, pad, pad, pad); addView(v) }
            add(cell(lead), 0, WRAP, 1.6f)
            for (f in figs) {
                add(a.vline(), WRAP, MATCH)
                add(cell(kpi(f.label, f.value, f.sub, color = f.color) { a.select(f.tab) }), 0, WRAP, 1f)
            }
        }
    }

    private fun kpi(label: String, value: String, sub: String, valueSp: Float = SIBLING_SP, color: Int = C.TEXT, onClick: () -> Unit): View =
        a.vbox {
            add(a.text(label, 15f, C.SUB, lines = 1))
            add(a.text(value, valueSp, color, bold = true, lines = 1, tabular = true), top = 8)
            add(a.text(sub, 14f, C.DIM), top = 6)
            background = a.pressable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setOnClickListener { onClick() }
        }

    /** 최근 기록 5건과 전체 보기. 정상 값은 잉크, 처리할 것만 색 */
    private fun recent(): View = a.card {
        add(a.hbox {
            add(a.text("최근 자동 기록", 20f, bold = true), 0, WRAP, 1f)
            add(a.text("운행일지 전체 보기", 16f, C.SUB).apply {
                setCompoundDrawablesRelative(null, null, a.iconDrawable(Ic.CHEVRON_RIGHT, C.SUB, 18), null)
                compoundDrawablePadding = a.dp(4)
                setPadding(a.dp(8), a.dp(8), 0, a.dp(8))
                setOnClickListener { a.select(MainActivity.Tab.LOGBOOK) }
            }, WRAP, WRAP)
        })
        data class Row(val time: Long, val icon: Ic, val title: String, val sub: String, val right: String, val color: Int)
        val rows = c.trips.take(RECENT_ROWS).map {
            Row(
                it.startTime, Ic.CAR, "${it.fromName.ifEmpty { "-" }} → ${it.toName.ifEmpty { "미등록 장소" }}",
                "${it.purpose.label} · ${it.classifiedBy.label}", AppController.fmtKm(it.distanceKm), purposeColor(it.purpose),
            )
        } + c.expenses.take(RECENT_ROWS).map {
            Row(
                it.time, it.category.ic, it.category.label + if (it.memo.isNotEmpty()) " · ${it.memo}" else "",
                it.source.label, if (it.pendingAmount) "금액 입력 필요" else AppController.won(it.amountWon),
                if (it.pendingAmount) C.AMBER else C.TEXT,
            )
        }
        val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREAN).apply { timeZone = TaxReport.KST }
        rows.sortedByDescending { it.time }.take(RECENT_ROWS).forEachIndexed { i, r ->
            if (i > 0) add(a.divider(), top = 12)
            add(a.hbox {
                add(a.icon(r.icon, C.SUB, 20), WRAP, WRAP, end = 16)
                add(a.vbox {
                    add(a.text(r.title, 17f))
                    add(a.text("${fmt.format(r.time)} · ${r.sub}", 14f, C.DIM), top = 4)
                }, 0, WRAP, 1f)
                add(a.text(r.right, 18f, r.color, bold = true, tabular = true), WRAP, WRAP)
            }, top = 12)
        }
    }

    companion object {
        // 큰 화면에서 핵심 48sp · 형제 30sp (1.6배). 기본 화면은 38 · 24
        private const val LEAD_SP = 38f
        private const val SIBLING_SP = 24f
        private const val RECENT_ROWS = 5

        /** 운행 목적 표시: 정상은 잉크, 손봐야 할 미분류만 색 */
        fun purposeColor(p: TripPurpose) = if (p == TripPurpose.UNCLASSIFIED) C.AMBER else C.TEXT

        fun purposeBg(p: TripPurpose) = if (p == TripPurpose.UNCLASSIFIED) C.AMBER_BG else C.SURFACE2
    }
}
