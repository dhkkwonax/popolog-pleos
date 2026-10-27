package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.KoreanAmountParser
import ai.chagok.app.core.TaxReport
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.ExpenseSource
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 월 단위 화면 공용: 이번 달 기준 offset 개월의 [start, end) 범위 */
data class MonthRange(val start: Long, val end: Long, val label: String) {
    fun contains(t: Long) = t in start until end

    companion object {
        fun of(now: Long, offset: Int): MonthRange {
            val c = Calendar.getInstance(TaxReport.KST).apply {
                timeInMillis = now
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.MONTH, offset)
            }
            val start = c.timeInMillis
            val label = "${c.get(Calendar.YEAR)}년 ${c.get(Calendar.MONTH) + 1}월"
            c.add(Calendar.MONTH, 1)
            return MonthRange(start, c.timeInMillis, label)
        }
    }
}

fun MainActivity.monthHeader(range: MonthRange, right: View? = null): View = hbox {
    add(button("‹", BtnStyle.SECONDARY) { monthOffset -= 1; render() }, dp(72), WRAP, end = 12)
    add(text(range.label, 24f, bold = true).apply { gravity = Gravity.CENTER }, WRAP, WRAP, end = 12)
    add(button("›", BtnStyle.SECONDARY, enabled = monthOffset < 0) { monthOffset += 1; render() }, dp(72), WRAP)
    add(View(context), 0, 1, 1f)
    if (right != null) add(right, WRAP, WRAP)
}

class LedgerScreen(private val a: MainActivity) {
    private val c = a.controller

    fun render(): View = a.vbox {
        val range = MonthRange.of(c.gateway.now(), a.monthOffset)
        val list = c.expenses.filter { range.contains(it.time) }
        val done = list.filter { !it.pendingAmount }
        val total = done.sumOf { it.amountWon }

        add(a.monthHeader(range, a.button("+ 비용 추가", BtnStyle.PRIMARY) { addExpenseDialog() }))

        add(a.pair(
            a.card {
                add(a.text("이번 달 차량비", 15f, C.SUB))
                add(a.text(AppController.won(total), 34f, bold = true), top = 10)
                val auto = done.count { it.source != ExpenseSource.MANUAL }
                add(a.text("${done.size}건 중 ${auto}건 자동·음성 기록", 15f, C.DIM), top = 10)
                val km = c.trips.filter { range.contains(it.startTime) }.sumOf { it.distanceKm }
                if (km > 0) add(a.text("km당 ${Math.round(total / km)}원 · 주행 ${AppController.fmtKm(km)}", 15f, C.DIM), top = 6)
            },
            a.card {
                add(a.text("항목별", 15f, C.SUB))
                val byCat = done.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountWon } }
                    .entries.sortedByDescending { it.value }
                if (byCat.isEmpty()) add(a.text("기록 없음", 16f, C.DIM), top = 10)
                for ((cat, sum) in byCat) {
                    add(a.hbox {
                        add(a.text("${cat.emoji} ${cat.label}", 16f), a.dp(130), WRAP)
                        add(a.bar(sum.toDouble() / total.coerceAtLeast(1), C.ACCENT), 0, WRAP, 1f, end = 12)
                        add(a.text(AppController.won(sum), 16f, bold = true).apply { gravity = Gravity.END }, a.dp(130), WRAP)
                    }, top = 10)
                }
            },
            secondWeight = 1.6f,
        ), top = 16)

        add(a.card {
            if (list.isEmpty()) add(a.text("이 달에는 기록이 없어요", 17f, C.DIM))
            val fmt = SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREAN).apply { timeZone = TaxReport.KST }
            list.forEachIndexed { i, e ->
                if (i > 0) add(a.divider(), top = 12)
                add(row(e, fmt), top = if (i == 0) 0 else 12)
            }
        }, top = 16)
    }

    private fun row(e: Expense, fmt: SimpleDateFormat): View = a.hbox {
        add(a.text(e.category.emoji, 24f), a.dp(44), WRAP)
        add(a.vbox {
            add(a.text(e.category.label + if (e.memo.isNotEmpty()) " · ${e.memo}" else "", 17f, lines = 1))
            val extra = listOfNotNull(
                fmt.format(e.time),
                e.quantity?.let { AppController.fmt1(it) + if (e.category == ExpenseCategory.CHARGE) "kWh" else "L" },
                e.odoKm?.let { "계기판 " + AppController.fmtKm(it) },
            ).joinToString(" · ")
            add(a.text(extra, 14f, C.DIM, lines = 1), top = 4)
        }, 0, WRAP, 1f)
        val srcColor = when (e.source) {
            ExpenseSource.AUTO -> C.ACCENT to C.ACCENT_BG
            ExpenseSource.VOICE -> C.BLUE to C.BLUE_BG
            ExpenseSource.MANUAL -> C.SUB to C.SURFACE2
        }
        add(a.chip(e.source.label, srcColor.first, srcColor.second, 13f), WRAP, WRAP, end = 14)
        if (e.pendingAmount) {
            add(a.button("금액 입력", BtnStyle.WARN) { a.askAmountByKeypad(e) }, WRAP, WRAP)
        } else {
            add(a.text(AppController.won(e.amountWon), 19f, bold = true).apply { gravity = Gravity.END }, a.dp(140), WRAP)
        }
        setOnLongClickListener {
            a.dialog("이 기록을 삭제할까요?", a.text("  ${e.category.label} ${AppController.won(e.amountWon)}", 17f, C.SUB), "삭제") {
                c.deleteExpense(e.id)
            }
            true
        }
    }

    private fun addExpenseDialog() {
        var category = ExpenseCategory.FUEL
        val amount = a.editText("금액 (원)", InputType.TYPE_CLASS_NUMBER)
        val memo = a.editText("메모 (선택)", InputType.TYPE_CLASS_TEXT)
        val chips = a.hbox()
        fun paint() {
            for (i in 0 until chips.childCount) {
                val v = chips.getChildAt(i)
                val sel = v.tag == category
                v.background = a.rounded(if (sel) C.ACCENT_BG else C.SURFACE2, 999, if (sel) C.ACCENT else null)
            }
        }
        for (cat in ExpenseCategory.values()) {
            chips.add(a.chip("${cat.emoji} ${cat.label}", C.TEXT, C.SURFACE2, 16f).apply {
                tag = cat
                minHeight = a.touchHeight(52)
                setOnClickListener { category = cat; paint() }
            }, WRAP, WRAP, end = 8)
        }
        paint()
        val body = a.vbox {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
            add(HorizontalScrollView(a).apply { addView(chips); isHorizontalScrollBarEnabled = false })
            add(a.hbox {
                add(amount, 0, WRAP, 1f, end = 10)
                add(a.button("🎤") {
                    a.listen("금액을 말씀해 주세요", "예: 삼만 오천 원") { said ->
                        KoreanAmountParser.parse(said)?.let { amount.setText(it.toString()) }
                    }
                }, a.dp(72), WRAP)
            }, top = 14)
            add(memo, top = 10)
        }
        a.dialog("비용 추가", body, "저장") {
            val v = amount.text.toString().toLongOrNull() ?: return@dialog
            c.saveExpense(
                Expense(
                    time = c.gateway.now(), category = category, amountWon = v, odoKm = c.odoKm,
                    memo = memo.text.toString(), source = ExpenseSource.MANUAL,
                ),
            )
        }
    }
}
