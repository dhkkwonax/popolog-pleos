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

    fun render(): View = a.vbox {
        c.driverGreeting?.let { add(greeting(it), top = 4) }
        c.pendingExpense()?.let { add(expensePrompt(it), top = 4) }
        c.pendingTrip()?.let { add(tripPrompt(it), top = 12) }
        if (c.lastNotice.isNotEmpty()) {
            add(a.hbox {
                add(a.text("✓", 18f, C.ACCENT, bold = true), WRAP, WRAP, end = 10)
                add(a.text(c.lastNotice, 17f, C.SUB, lines = 1), 0, WRAP, 1f)
            }, top = 12)
        }
        add(kpis(), top = 16)
        add(recent(), top = 16)
    }

    /** 시동 직후 잠깐: 누구로 인식했는지와 바꾸는 방법 */
    private fun greeting(text: String): View = a.card(C.BLUE_BG, stroke = C.BLUE) {
        val d = c.driver
        add(a.hbox {
            add(a.text("👤", 30f), WRAP, WRAP, end = 16)
            add(a.vbox {
                add(a.text(text, 22f, bold = true))
                // "직접 선택으로" / "운전석 시트 위치로": 받침 유무로 조사를 고른다
                val how = d?.let {
                    val by = if (it.method == DriverMethod.MANUAL) "직접 선택으로 바꿈" else "${it.method.label}로 인식"
                    "$by · ${it.driver.department} · 오늘 운행은 이 이름으로 기록됩니다"
                }.orEmpty()
                add(a.text(how, 16f, C.SUB), top = 6)
            }, 0, WRAP, 1f)
            add(a.button("다른 분이에요", BtnStyle.SECONDARY) { a.pickDriver() }, WRAP, WRAP)
        })
    }

    private fun expensePrompt(e: ai.chagok.app.model.Expense): View = a.card(C.AMBER_BG, stroke = C.AMBER) {
        val unit = if (e.category == ExpenseCategory.CHARGE) "kWh" else "L"
        add(a.hbox {
            add(a.text(e.category.emoji, 34f), WRAP, WRAP, end = 16)
            add(a.vbox {
                // "주유가" / "충전이" — 받침 유무로 조사를 고른다
                val subject = if (e.category == ExpenseCategory.CHARGE) "충전이" else "주유가"
                add(a.text("$subject 감지됐어요 · 약 ${AppController.fmt1(e.quantity ?: 0.0)}$unit", 22f, bold = true))
                val gauge = if (e.category == ExpenseCategory.CHARGE) "배터리" else "연료"
                add(a.text("$gauge ${e.memo} · 결제 금액만 말씀하시면 장부에 들어갑니다", 16f, C.SUB), top = 6)
            }, 0, WRAP, 1f)
        })
        add(a.hbox {
            add(a.button("🎤 금액 말하기", BtnStyle.WARN) { a.askAmountByVoice(e) }, 0, WRAP, 1.2f, end = 10)
            add(a.button("직접 입력") { a.askAmountByKeypad(e) }, 0, WRAP, 1f, end = 10)
            add(a.button("나중에", BtnStyle.GHOST) { c.dismissExpenseQuestion() }, 0, WRAP, 0.8f)
        }, top = 18)
    }

    private fun tripPrompt(t: ai.chagok.app.model.Trip): View = a.card(C.BLUE_BG, stroke = C.BLUE) {
        add(a.hbox {
            add(a.text("🚗", 34f), WRAP, WRAP, end = 16)
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
            add(a.button("🎤 말하기", BtnStyle.PRIMARY) { a.askPurposeByVoice(t) }, 0, WRAP, 1f, end = 10)
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

        // 좁은 창(세로 1칸)에서는 네 칸이 금액을 "1,44…" 로 잘라 버려 2×2 로 놓는다
        return a.grid(
            if (a.isNarrow) 2 else 4,
            listOf(
                kpi("${m + 1}월 차량비", AppController.won(monthCost), "km당 ${Math.round(perKm)}원") { a.select(MainActivity.Tab.LEDGER) },
                kpi("${m + 1}월 주행", AppController.fmtKm(monthKm), "계기판 누적거리 기준") { a.select(MainActivity.Tab.LOGBOOK) },
                kpi("${y}년 업무사용비율", String.format(Locale.US, "%.1f%%", year.businessRatio * 100), "출퇴근 포함") { a.select(MainActivity.Tab.TAX) },
                kpi("확인할 항목", "${todo}건", if (todo == 0) "모두 정리됨" else "미분류 ${year.unclassifiedTrips} · 금액 ${year.pendingExpenses}", if (todo > 0) C.AMBER else C.ACCENT) {
                    a.select(MainActivity.Tab.LOGBOOK)
                },
            ),
        )
    }

    private fun kpi(label: String, value: String, sub: String, color: Int = C.TEXT, onClick: () -> Unit): View =
        a.card(padDp = 20) {
            add(a.text(label, 15f, C.SUB))
            add(a.text(value, 28f, color, bold = true, lines = 1), top = 10)
            add(a.text(sub, 14f, C.DIM, lines = 1), top = 8)
            background = a.pressable(a.rounded(C.SURFACE, 20))
            setOnClickListener { onClick() }
        }

    private fun recent(): View = a.card {
        add(a.text("최근 자동 기록", 20f, bold = true))
        data class Row(val time: Long, val icon: String, val title: String, val sub: String, val right: String, val color: Int)
        val rows = c.trips.take(6).map {
            Row(
                it.startTime, "🚗", "${it.fromName.ifEmpty { "-" }} → ${it.toName.ifEmpty { "미등록 장소" }}",
                "${it.purpose.label} · ${it.classifiedBy.label}", AppController.fmtKm(it.distanceKm), purposeColor(it.purpose),
            )
        } + c.expenses.take(6).map {
            Row(
                it.time, it.category.emoji, it.category.label + if (it.memo.isNotEmpty()) " · ${it.memo}" else "",
                it.source.label, if (it.pendingAmount) "금액 입력 필요" else AppController.won(it.amountWon),
                if (it.pendingAmount) C.AMBER else C.TEXT,
            )
        }
        val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREAN).apply { timeZone = TaxReport.KST }
        rows.sortedByDescending { it.time }.take(8).forEachIndexed { i, r ->
            if (i > 0) add(a.divider(), top = 12)
            add(a.hbox {
                add(a.text(r.icon, 22f), a.dp(40), WRAP)
                add(a.vbox {
                    add(a.text(r.title, 17f, lines = 1))
                    add(a.text("${fmt.format(r.time)} · ${r.sub}", 14f, C.DIM, lines = 1), top = 4)
                }, 0, WRAP, 1f)
                add(a.text(r.right, 18f, r.color, bold = true), WRAP, WRAP)
            }, top = 12)
        }
    }

    companion object {
        fun purposeColor(p: TripPurpose) = when (p) {
            TripPurpose.BUSINESS -> C.ACCENT
            TripPurpose.COMMUTE -> C.BLUE
            TripPurpose.PERSONAL -> C.SUB
            TripPurpose.UNCLASSIFIED -> C.AMBER
        }

        fun purposeBg(p: TripPurpose) = when (p) {
            TripPurpose.BUSINESS -> C.ACCENT_BG
            TripPurpose.COMMUTE -> C.BLUE_BG
            TripPurpose.PERSONAL -> C.SURFACE2
            TripPurpose.UNCLASSIFIED -> C.AMBER_BG
        }
    }
}
