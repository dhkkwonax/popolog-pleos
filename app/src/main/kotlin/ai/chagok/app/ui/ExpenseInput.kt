package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.MaintenancePlan
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.ExpenseDraft
import android.app.Dialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

/** 입력 화면의 종류 칩 (자동 감지·고정비 항목은 제외) */
private val INPUT_CATEGORIES = listOf(
    ExpenseCategory.MAINTENANCE, ExpenseCategory.TOLL, ExpenseCategory.PARKING, ExpenseCategory.WASH,
    ExpenseCategory.FUEL, ExpenseCategory.CHARGE, ExpenseCategory.OTHER,
)

/** 주행 중이면 입력 화면을 열지 않고 안내한다. 열어도 되면 true */
fun MainActivity.requireStopped(): Boolean {
    if (!controller.isDriving) return true
    dialog("정차한 뒤 입력해 주세요", text("  주행 중에는 비용을 입력할 수 없어요. 차를 세운 뒤 다시 열어 주세요.", 17f, C.SUB).apply {
        setPadding(dp(24), dp(4), dp(24), 0)
    }, null) {}
    return false
}

/**
 * 비용 입력 화면: 종류 칩, 정비 항목 칩, 결제한 곳(최근 목록), 큰 숫자패드. 정차 중에만 열린다.
 * 확인을 누르면 바로 저장하지 않고 확인 카드([showExpenseConfirm])를 거친다.
 */
fun MainActivity.showExpenseInput(initial: ExpenseDraft = ExpenseDraft()) {
    if (!requireStopped()) return
    val c = controller
    var category = initial.category
    val items = initial.items.toMutableSet()
    var digits = initial.amountWon?.toString().orEmpty()
    val shop = editText("정비소·주유소·주차장 (선택)", InputType.TYPE_CLASS_TEXT, initial.shop)
    val memo = editText("메모 (선택)", InputType.TYPE_CLASS_TEXT, initial.memo)
    val amountView = text("", 34f, bold = true, tabular = true).apply { gravity = Gravity.END }
    fun refreshAmount() {
        amountView.text = if (digits.isEmpty()) "0원" else AppController.won(digits.toLong())
    }
    refreshAmount()

    fun selectable(label: String, on: Boolean, click: () -> Unit): View = chip(label, C.TEXT, if (on) C.NAV_ACTIVE else C.SURFACE2, 16f).apply {
        if (on) background = rounded(C.NAV_ACTIVE, 999, C.SUB)
        minHeight = touchHeight(52)
        setOnClickListener { click() }
    }

    val categoryRow = hbox()
    val itemsRow = hbox()
    val itemsBox = vbox()
    // 가로 스크롤은 한 번만 만든다 (칩을 누를 때마다 다시 만들면 같은 행을 두 부모에 붙이게 된다)
    val itemsScroll = HorizontalScrollView(this).apply { addView(itemsRow); isHorizontalScrollBarEnabled = false }
    fun paint() {
        categoryRow.removeAllViews()
        for (cat in INPUT_CATEGORIES) categoryRow.add(selectable(cat.label, cat == category) { category = cat; paint() }, WRAP, WRAP, end = 8)
        itemsBox.removeAllViews()
        itemsRow.removeAllViews()
        if (category == ExpenseCategory.MAINTENANCE) {
            for (name in MaintenancePlan.CHIPS) {
                itemsRow.add(selectable(name, name in items) { if (!items.add(name)) items.remove(name); paint() }, WRAP, WRAP, end = 8)
            }
            itemsBox.add(text("정비 항목 (여러 개 고를 수 있어요)", 14f, C.SUB))
            itemsBox.add(itemsScroll, top = 8)
        }
    }
    paint()

    val recent = c.flows.recentShops()
    val recentRow = hbox()
    for (name in recent) recentRow.add(chip(name, C.TEXT, C.SURFACE2, 15f).apply {
        minHeight = touchHeight(48)
        setOnClickListener { shop.setText(name) }
    }, WRAP, WRAP, end = 8)

    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "⌫").map { k ->
        button(k, BtnStyle.SECONDARY) {
            digits = when (k) {
                "⌫" -> digits.dropLast(1)
                else -> (digits + k).trimStart('0').take(9)
            }
            refreshAmount()
        }
    }
    val form = vbox {
        add(text("종류", 14f, C.SUB))
        add(HorizontalScrollView(this@showExpenseInput).apply { addView(categoryRow); isHorizontalScrollBarEnabled = false }, top = 8)
        add(itemsBox, top = 14)
        add(shop, top = 14)
        if (recent.isNotEmpty()) {
            add(text("최근 간 곳", 14f, C.SUB), top = 10)
            add(HorizontalScrollView(this@showExpenseInput).apply { addView(recentRow); isHorizontalScrollBarEnabled = false }, top = 6)
        }
        add(memo, top = 10)
    }
    // 분할 창이면 화면 전체가 아니라 앱 창의 폭을 기준으로 한다
    val winWidth = window.decorView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    val dialogWidth = minOf(dp(1000), (winWidth * 0.94).toInt())
    // 대화상자가 좁으면(분할 창 등) 숫자패드를 옆이 아니라 아래에 둔다
    val narrowBody = isNarrow || winWidth < resources.displayMetrics.widthPixels * 0.5
    val pad = vbox {
        add(text("금액", 14f, C.SUB))
        add(amountView, top = 4)
        // 좁은 창에서는 4열로 줄여 세로로 덜 길게 한다
        add(grid(if (narrowBody) 4 else 3, keys, gapDp = 10), top = 12)
    }
    val body = if (narrowBody) vbox {
        setPadding(dp(24), dp(4), dp(24), dp(4))
        add(form); add(pad, top = 18)
    } else hbox(Gravity.TOP) {
        setPadding(dp(24), dp(4), dp(24), dp(4))
        add(form, 0, WRAP, 1.1f, end = 28)
        add(pad, 0, WRAP, 0.9f)
    }
    val scroll = ScrollView(this).apply { addView(body) }
    val d = dialog("비용 입력", scroll, "확인") {
        val amount = digits.toLongOrNull()
        if (amount == null || amount <= 0) {
            toast("금액을 입력해 주세요")
            showExpenseInput(initial.copy(category = category, items = items.toList(), shop = shop.text.toString(), memo = memo.text.toString(), amountWon = null))
        } else {
            showExpenseConfirm(
                ExpenseDraft(category, amount, shop.text.toString().trim(), items.toList(), memo.text.toString().trim(), initial.source),
            )
        }
    }
    scroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.7).toInt())
    d.window?.setLayout(dialogWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
}

/** 확인 카드: 저장하기 전에 내용을 한 번 보여 준다. '수정'은 입력 화면으로 돌아간다 */
fun MainActivity.showExpenseConfirm(draft: ExpenseDraft) {
    if (!requireStopped()) return
    val c = controller
    val amount = draft.amountWon
    if (amount == null) {
        showExpenseInput(draft)
        return
    }
    fun row(k: String, v: String): View = hbox {
        add(text(k, 16f, C.SUB), dp(110), WRAP)
        add(text(v, 18f, bold = true), 0, WRAP, 1f)
    }
    val body = vbox {
        setPadding(dp(24), dp(8), dp(24), dp(4))
        add(row("종류", draft.category.label + if (draft.items.isNotEmpty()) " · " + draft.items.joinToString(", ") else ""))
        add(row("금액", AppController.won(amount)), top = 10)
        if (draft.shop.isNotBlank()) add(row("결제한 곳", draft.shop), top = 10)
        if (draft.memo.isNotBlank()) add(row("메모", draft.memo), top = 10)
        add(row("계기판", AppController.fmtKm(c.odoKm)), top = 10)
        if (draft.source == ai.chagok.app.model.ExpenseSource.VOICE) add(text("음성으로 받은 내용이에요. 맞는지 확인해 주세요.", 14f, C.DIM), top = 12)
    }
    var dlg: Dialog? = null
    dlg = dialog("이 내용으로 저장할까요?", body, "저장", listOf("수정" to {
        dlg?.dismiss()
        showExpenseInput(draft)
        Unit
    })) {
        c.saveDraft(draft)
    }
}

/** 금액만 받는 숫자패드 창 (주유·충전 금액, 음성 대답의 직접 입력) */
fun MainActivity.askAmountPad(title: String, hint: String = "", unit: String = "원", onAmount: (Long) -> Unit) {
    var digits = ""
    val amountView = text("0$unit", 34f, bold = true, tabular = true).apply { gravity = Gravity.END }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "⌫").map { k ->
        button(k, BtnStyle.SECONDARY) {
            digits = if (k == "⌫") digits.dropLast(1) else (digits + k).trimStart('0').take(9)
            amountView.text = if (digits.isEmpty()) "0$unit" else String.format(java.util.Locale.US, "%,d%s", digits.toLong(), unit)
        }
    }
    val body = vbox {
        setPadding(dp(24), dp(4), dp(24), dp(4))
        if (hint.isNotEmpty()) add(text(hint, 15f, C.SUB))
        add(amountView, top = 8)
        add(grid(3, keys, gapDp = 10), top = 12)
    }
    val d = dialog(title, body, "확인") {
        val v = digits.toLongOrNull()
        if (v != null && v > 0) onAmount(v) else toast("금액을 입력해 주세요")
    }
    d.window?.setLayout(minOf(dp(560), (resources.displayMetrics.widthPixels * 0.92).toInt()), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
}

/** 이름 같은 짧은 글을 직접 입력받는 창 */
fun MainActivity.askTextInput(title: String, hint: String, onText: (String) -> Unit) {
    val input = editText(hint, InputType.TYPE_CLASS_TEXT)
    val body = vbox {
        setPadding(dp(24), dp(8), dp(24), 0)
        add(input)
    }
    dialog(title, body, "확인") {
        val s = input.text.toString().trim()
        if (s.isNotEmpty()) onText(s)
    }
}
