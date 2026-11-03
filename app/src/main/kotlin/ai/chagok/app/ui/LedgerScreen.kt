package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
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

    fun render(): View {
        if (!c.canManage) return a.adminGate()
        return a.vbox {
        val range = MonthRange.of(c.gateway.now(), a.monthOffset)
        val list = c.expenses.filter { range.contains(it.time) }
        val done = list.filter { !it.pendingAmount }
        val total = done.sumOf { it.amountWon }

        add(a.monthHeader(range, a.button("+ 비용 추가", BtnStyle.PRIMARY) { a.showExpenseInput() }))
        syncInbox()?.let { add(it, top = 14) }

        add(a.pair(
            a.card {
                add(a.text("이번 달 차량비", 15f, C.SUB))
                add(a.text(AppController.won(total), 38f, bold = true, tabular = true), top = 10)
                val auto = done.count { it.source != ExpenseSource.MANUAL }
                add(a.text("${done.size}건 중 ${auto}건 자동·음성 기록", 15f, C.DIM), top = 10)
                val km = c.trips.filter { range.contains(it.startTime) }.sumOf { it.distanceKm }
                if (km > 0) add(a.text("km당 ${Math.round(total / km)}원 · 주행 ${AppController.fmtKm(km)}", 15f, C.DIM), top = 6)
                // 옆 카드와 아래 끝을 맞추고, 남는 높이 아래에 다음 할 일을 붙인다
                addFiller()
                add(a.text("세무 리포트에서 비용 인정액 보기", 16f, C.SUB).apply {
                    setCompoundDrawablesRelative(null, null, a.iconDrawable(Ic.CHEVRON_RIGHT, C.SUB, 18), null)
                    compoundDrawablePadding = a.dp(4)
                    setPadding(0, a.dp(12), 0, 0)
                    setOnClickListener { a.select(MainActivity.Tab.TAX) }
                }, WRAP, WRAP)
            },
            a.card {
                add(a.text("항목별", 15f, C.SUB))
                val byCat = done.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountWon } }
                    .entries.sortedByDescending { it.value }
                if (byCat.isEmpty()) add(a.text("기록 없음", 16f, C.DIM), top = 10)
                for ((cat, sum) in byCat) {
                    add(a.hbox {
                        add(a.icon(cat.ic, C.SUB, 18), WRAP, WRAP, end = 10)
                        add(a.text(cat.label, 16f), a.dp(100), WRAP)
                        add(a.bar(sum.toDouble() / total.coerceAtLeast(1), C.ACCENT), 0, WRAP, 1f, end = 12)
                        add(a.text(AppController.won(sum), 16f, bold = true, tabular = true).apply { gravity = Gravity.END }, a.dp(130), WRAP)
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

    }

    private fun row(e: Expense, fmt: SimpleDateFormat): View = a.hbox {
        add(a.icon(e.category.ic, C.SUB, 22), WRAP, WRAP, end = 18)
        add(a.vbox {
            add(a.text(e.category.label + if (e.memo.isNotEmpty()) " · ${e.memo}" else "", 17f))
            val extra = listOfNotNull(
                fmt.format(e.time),
                e.quantity?.let { AppController.fmt1(it) + if (e.category == ExpenseCategory.CHARGE) "kWh" else "L" },
                e.odoKm?.let { "계기판 " + AppController.fmtKm(it) },
                // 입력 방식은 따로 알약을 달지 않고 설명 줄에 둔다 (행마다 상태 표시는 하나)
                e.source.label,
            ).joinToString(" · ")
            add(a.text(extra, 14f, C.DIM), top = 4)
        }, 0, WRAP, 1f)
        if (e.pendingAmount) {
            add(a.button("금액 입력", BtnStyle.WARN) { a.askAmountByKeypad(e) }, WRAP, WRAP)
        } else {
            add(a.text(AppController.won(e.amountWon), 19f, bold = true, tabular = true).apply { gravity = Gravity.END }, a.dp(140), WRAP)
        }
        setOnLongClickListener {
            a.dialog("이 기록을 삭제할까요?", a.text("  ${e.category.label} ${AppController.won(e.amountWon)}", 17f, C.SUB), "삭제") {
                c.deleteExpense(e.id)
            }
            true
        }
    }

    /** 외부 연동으로 들어온 '검토 필요' 내역. 승인하면 장부에 넣고, 이미 입력한 같은 기록이 있으면 병합을 권한다 */
    private fun syncInbox(): View? {
        val items = c.flows.syncReview()
        if (items.isEmpty()) return null
        val fmt = SimpleDateFormat("M/d", Locale.KOREAN).apply { timeZone = TaxReport.KST }
        return a.card(C.SURFACE, stroke = C.AMBER) {
            add(a.text("연동 내역 ${items.size}건 · 검토 필요", 19f, bold = true))
            add(a.text("승인하면 장부에 들어가요. 거절하면 들어가지 않아요.", 14f, C.SUB), top = 4)
            items.forEachIndexed { i, it ->
                if (i > 0) add(a.divider(), top = 12)
                val dup = c.flows.similar(it)
                add(a.hbox(Gravity.CENTER_VERTICAL) {
                    add(a.icon(it.category.ic, C.SUB, 22), WRAP, WRAP, end = 14)
                    add(a.vbox {
                        add(a.text("${it.category.label}${if (it.memo.isNotEmpty()) " · " + it.memo else ""}", 17f))
                        val line = listOf(fmt.format(it.time), it.shop).filter { s -> s.isNotEmpty() }.joinToString(" · ")
                        add(a.text(line, 14f, C.DIM), top = 2)
                        if (dup != null) add(a.text("이미 입력한 같은 기록이 있어요 (${fmt.format(dup.time)})", 14f, C.AMBER), top = 2)
                    }, 0, WRAP, 1f, end = 10)
                    add(a.text(AppController.won(it.amountWon), 18f, bold = true, tabular = true), WRAP, WRAP, end = 12)
                    if (dup != null) add(a.button("병합", BtnStyle.SECONDARY) { c.flows.approve(it, merge = true) }, WRAP, WRAP, end = 8)
                    add(a.button(if (dup != null) "따로 추가" else "승인", BtnStyle.PRIMARY) { c.flows.approve(it, merge = false) }, WRAP, WRAP, end = 8)
                    add(a.button("거절", BtnStyle.GHOST) { c.flows.reject(it) }, WRAP, WRAP)
                }, top = 12)
            }
        }
    }
}
