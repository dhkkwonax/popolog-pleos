package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.GleoParams
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.ExpenseDraft
import ai.chagok.app.model.ExpenseSource
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject

/** Gleo 가 앱 함수를 부를 때 쓸 수 있는 인텐트 액션 이름(함수 이름) */
private val GLEO_ACTIONS = setOf(GleoParams.ADD_EXPENSE, GleoParams.QUERY_REPORT)

/**
 * Gleo 의 함수 호출을 받는다. 파라미터는 인텐트의 JSON 문자열 extra(`ACTION_PARAM`)로 온다고 가정하고,
 * 다른 이름의 extra·중첩 JSON·낱개 extra 도 받아 준다. 실제 Gleo 가 보내는 모양은 아직 확인하지 못했다.
 * 처리한 인텐트는 다시 처리하지 않도록 액션을 비운다.
 */
fun MainActivity.handleGleo(i: Intent?) {
    val action = i?.action?.substringAfterLast('.') ?: return
    if (action !in GLEO_ACTIONS) return
    val params = gleoParams(i)
    i.action = null
    when (action) {
        GleoParams.ADD_EXPENSE -> gleoAddExpense(GleoParams.addExpense(params))
        GleoParams.QUERY_REPORT -> gleoQuery(GleoParams.query(params))
    }
}

/** 인텐트에서 파라미터 맵을 꺼낸다 */
fun gleoParams(i: Intent): Map<String, Any?> {
    val extras = i.extras ?: return emptyMap()
    for (key in listOf("ACTION_PARAM", "action_param", "ACTION_PARAMS", "params", "parameters", "arguments")) {
        val raw = extras.get(key) ?: continue
        val map = when (raw) {
            is String -> runCatching { jsonToMap(JSONObject(raw)) }.getOrNull()
            is Bundle -> raw.keySet().associateWith { raw.get(it) }
            else -> null
        } ?: continue
        // {"function": "...", "arguments": {...}} 처럼 한 겹 더 싸여 오는 경우
        for (inner in listOf("arguments", "parameters", "params")) {
            (map[inner] as? Map<*, *>)?.let { m -> return m.entries.associate { it.key.toString() to it.value } }
        }
        return map
    }
    return extras.keySet().associateWith { extras.get(it) }
}

private fun jsonToMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k ->
    when (val v = o.get(k)) {
        is JSONObject -> jsonToMap(v)
        JSONObject.NULL -> null
        else -> v
    }
}

/** 음성으로 받은 비용: 금액이 있으면 확인 카드로, 없으면 입력 화면으로 이어 준다 */
private fun MainActivity.gleoAddExpense(p: GleoParams.AddExpense) {
    val draft = ExpenseDraft(
        category = p.category ?: ExpenseCategory.OTHER, amountWon = p.amountWon, shop = p.place, source = ExpenseSource.VOICE,
    )
    if (!requireStopped()) return
    if (draft.amountWon != null) showExpenseConfirm(draft) else showExpenseInput(draft)
}

/** 기간별 조회·내보내기 */
private fun MainActivity.gleoQuery(q: GleoParams.Query) {
    when (q.action) {
        GleoParams.QueryAction.EXPORT_PDF -> {
            select(MainActivity.Tab.TAX)
            TaxScreen(this).exportPdfForYear()
        }
        GleoParams.QueryAction.EXPORT_CSV -> {
            select(MainActivity.Tab.TAX)
            TaxScreen(this).exportExpensesForYear()
        }
        GleoParams.QueryAction.SHOW -> showReport(q)
    }
}

private fun MainActivity.showReport(q: GleoParams.Query) {
    val c = controller
    val range = GleoParams.range(q.period, c.gateway.now())
    val list = c.expenses.filter { it.time in range && !it.pendingAmount && (q.category == null || it.category == q.category) }
        .sortedByDescending { it.time }
    val total = list.sumOf { it.amountWon }
    val name = "${GleoParams.label(q.period)} ${q.category?.label ?: "차량"} 비용"
    val fmt = java.text.SimpleDateFormat("M/d", java.util.Locale.KOREAN).apply { timeZone = ai.chagok.app.core.TaxReport.KST }
    val body = vbox {
        setPadding(dp(24), dp(8), dp(24), dp(4))
        add(text(AppController.won(total), 34f, bold = true, tabular = true))
        add(text("${list.size}건", 16f, C.SUB), top = 4)
        for (e in list.take(6)) {
            add(hbox {
                add(text("${fmt.format(e.time)} · ${e.category.label}" + if (e.shop.isNotEmpty()) " · ${e.shop}" else "", 16f), 0, WRAP, 1f, end = 10)
                add(text(AppController.won(e.amountWon), 16f, bold = true, tabular = true), WRAP, WRAP)
            }, top = 10)
        }
        if (list.size > 6) add(text("외 ${list.size - 6}건", 14f, C.DIM), top = 10)
    }
    dialog(name, body, null) {}
    c.speak("$name 은 ${list.size}건, ${AppController.amountSpoken(total)} 이에요.")
}

/** 시연: Gleo 가 보내는 것과 같은 인텐트를 앱 자신에게 보낸다 (manifest 필터와 수신 경로를 그대로 거친다) */
fun MainActivity.simulateGleo(function: String, json: String) {
    startActivity(
        Intent(function).setPackage(packageName).addCategory(Intent.CATEGORY_DEFAULT).putExtra("ACTION_PARAM", json),
    )
}
