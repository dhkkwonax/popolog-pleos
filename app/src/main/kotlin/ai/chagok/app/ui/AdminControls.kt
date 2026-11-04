package ai.chagok.app.ui

import ai.chagok.app.MainActivity
import android.app.Dialog
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.Executors

private val adminIo = Executors.newSingleThreadExecutor()

fun MainActivity.adminControls(): View = card {
    val auth = controller.admin
    add(text("차량 관리자", 20f, bold = true))
    add(text(when {
        controller.canManage -> "관리자 모드 사용 중"
        auth.configured -> "관리자 모드 잠김"
        else -> "관리자 PIN을 설정해 주세요"
    }, 24f, bold = true), top = 12)
    add(text("기본 운행·비용 기록은 누구나 사용할 수 있어요. 과거 운행과 세무 자료는 관리자가 확인합니다.", 16f, C.SUB), top = 10)
    if (controller.canManage) {
        add(button("관리 종료", BtnStyle.PRIMARY) { lockAdmin() }, WRAP, WRAP, top = 18)
        add(button("PIN 변경·관리자 인계", BtnStyle.SECONDARY) { changeAdmin() }, WRAP, WRAP, top = 10)
    } else {
        add(button(if (auth.configured) "관리자 잠금 해제" else "관리자 PIN 설정", BtnStyle.PRIMARY) { requestAdmin() }, WRAP, WRAP, top = 18)
        if (auth.configured) add(button("PIN을 잊었어요", BtnStyle.GHOST) { recoverAdmin() }, WRAP, WRAP, top = 8)
    }
    add(text("앱을 벗어나거나 주행을 시작하면 다시 잠깁니다. Pleos 계정과 연결되지 않는 이 차량의 로컬 잠금입니다.", 14f, C.SUB), top = 16)
}

fun MainActivity.adminGate(): View = vbox {
    add(text("관리자가 확인하는 기록", 26f, bold = true), top = 18)
    add(text("과거 운행 내역과 세무 자료를 보려면\n정차 중 관리자 PIN으로 잠금을 해제해 주세요.", 17f, C.SUB), top = 12)
    add(adminControls(), top = 24)
    add(button("비용 추가", BtnStyle.SECONDARY) { showExpenseInput() }, WRAP, WRAP, top = 16)
    add(button("차량 요약으로", BtnStyle.GHOST) { select(MainActivity.Tab.HOME) }, WRAP, WRAP, top = 16)
}

fun MainActivity.requireAdmin(): Boolean {
    if (controller.canManage) return true
    if (controller.dataSuspended) { toast("기록 작업이 중단되어 있습니다"); return false }
    if (controller.isDriving) { toast("관리자 기능은 정차 후 이용해 주세요"); return false }
    toast("관리자 잠금을 해제한 뒤 다시 실행해 주세요")
    select(MainActivity.Tab.SETTINGS)
    return false
}

fun MainActivity.requestAdmin() {
    if (!requireStopped()) return
    if (!controller.admin.configured) { setupAdmin(); return }
    pinDialog("관리자 잠금 해제", "이 차량에 설정한 숫자 6자리 PIN", "잠금 해제") { pin, dialog, status ->
        val generation = controller.admin.generation
        runAdmin(status, { check(controller.admin.authenticate(pin, generation)) { "PIN이 일치하지 않습니다" } }) {
            controller.admin.openSession(generation)
            dialog.dismiss()
            render()
            toast("관리자 모드를 열었어요. 원하는 작업을 다시 선택해 주세요")
        }
    }
}

fun MainActivity.reauthenticateAdmin(onVerified: () -> Unit) {
    if (!requireAdmin()) return
    pinDialog("관리자 PIN 재확인", "중요한 작업입니다. 현재 PIN을 다시 입력해 주세요.", "확인") { pin, dialog, status ->
        val generation = controller.admin.generation
        runAdmin(status, { check(controller.admin.authenticate(pin, generation)) { "PIN이 일치하지 않습니다" } }) {
            controller.admin.openSession(generation)
            dialog.dismiss()
            onVerified()
        }
    }
}

private fun MainActivity.changeAdmin() {
    reauthenticateAdmin { setupAdmin(replacing = true) }
}

private fun MainActivity.setupAdmin(replacing: Boolean = false) {
    if (!requireStopped()) return
    pinDialog(if (replacing) "새 관리자 PIN" else "관리자 PIN 설정", "숫자 6자리 · 차량 소유자 또는 회사 담당자가 설정해 주세요.", "다음") { first, dialog, _ ->
        dialog.dismiss()
        pinDialog("PIN을 한 번 더 입력해 주세요", "앞에서 입력한 숫자 6자리를 확인합니다.", "PIN 저장") { second, confirmation, status ->
            if (first != second) { status.text = "PIN이 서로 다릅니다. 다시 입력해 주세요"; return@pinDialog }
            val generation = controller.admin.generation
            runAdmin(status, {
                if (replacing) controller.admin.replace(second, generation) else controller.admin.setup(second, generation)
            }) { code ->
                confirmation.dismiss()
                showRecoveryCode(code)
            }
        }
    }
}

private fun MainActivity.showRecoveryCode(code: String) {
    render()
    val body = vbox {
        setPadding(dp(24), dp(8), dp(24), 0)
        add(text("복구 코드는 지금 한 번만 표시됩니다", 18f, bold = true))
        add(text("차량 밖의 안전한 곳에 적어 두세요. PIN과 복구 코드를 모두 잃으면 기록을 유지한 채 잠금을 풀 수 없고, 앱을 지운 뒤 암호화된 백업도 복구할 수 없습니다.", 16f, C.SUB), top = 12)
        add(text(code.chunked(20).joinToString("\n"), 22f, bold = true, tabular = true), top = 20)
        add(text("관리자 인계·PIN 복구 시 기존 코드는 폐기됩니다.", 14f, C.SUB), top = 16)
    }
    dialog("복구 코드를 보관해 주세요", body, "보관했어요") {
        toast("PIN을 저장했어요. 관리자 잠금을 해제해 주세요")
        render()
    }.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

private fun MainActivity.recoverAdmin() {
    if (!requireStopped()) return
    val code = editText("복구 코드", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).apply {
        filters = arrayOf(InputFilter.LengthFilter(48))
        contentDescription = "복구 코드 입력"
    }
    val body = vbox {
        setPadding(dp(24), dp(8), dp(24), 0)
        add(text("처음 설정할 때 별도로 보관한 복구 코드를 입력하세요. 기존 차량 기록은 유지합니다.", 16f, C.SUB))
        add(code, top = 16)
    }
    dialog("관리자 PIN 복구", body, "새 PIN 입력") {
        val recovery = code.text.toString()
        code.text.clear()
        pinDialog("새 PIN 설정", "복구 코드를 확인하고 숫자 6자리 PIN을 저장합니다.", "복구") { pin, d, status ->
            val generation = controller.admin.generation
            runAdmin(status, { controller.admin.recover(recovery, pin, generation) }) { newCode ->
                d.dismiss()
                showRecoveryCode(newCode)
            }
        }
    }.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

private fun MainActivity.pinDialog(title: String, detail: String, action: String, submit: (String, Dialog, TextView) -> Unit) {
    var value = ""
    var working = false
    val digits = text("○  ○  ○  ○  ○  ○", 28f, bold = true).apply { contentDescription = "PIN 0자리 입력" }
    val status = text(detail, 15f, C.SUB)
    lateinit var d: Dialog
    var dialogRef: Dialog? = null
    lateinit var send: TextView
    fun update() {
        digits.text = (0..5).joinToString("  ") { if (it < value.length) "●" else "○" }
        digits.contentDescription = "PIN ${value.length}자리 입력"
        send.isEnabled = value.length == 6 && !working
        send.alpha = if (send.isEnabled) 1f else 0.45f
        dialogRef?.setCancelable(!working)
        dialogRef?.window?.decorView?.findViewWithTag<View>("dialog-close")?.isEnabled = !working
    }
    val body = vbox {
        setPadding(dp(24), dp(8), dp(24), 0)
        add(status)
        add(digits, top = 14)
        val keys = (1..9).map(Int::toString) + listOf("지우기", "0", "⌫")
        add(grid(3, keys.map { key -> button(key, BtnStyle.SECONDARY) {
            if (!working) {
                value = when (key) {
                    "지우기" -> ""
                    "⌫" -> value.dropLast(1)
                    else -> if (value.length < 6) value + key else value
                }
                update()
            }
        } }, gapDp = 8), top = 18)
        send = button(action, BtnStyle.PRIMARY) {
            if (value.length == 6 && !working && requireStopped()) {
                val pin = value
                value = ""
                working = true
                update()
                submit(pin, d, status)

            }
        }
        add(send, top = 12)
    }
    status.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (s.toString() != "확인 중입니다…") { working = false; update() }
        }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    })
    update()
    d = dialog(title, body, null) {}
    dialogRef = d
}

private fun <T> MainActivity.runAdmin(status: TextView, work: () -> T, done: (T) -> Unit) {
    val epoch = adminUiEpoch
    status.text = "확인 중입니다…"
    adminIo.execute {
        val result = runCatching(work)
        runOnUiThread {
            if (isFinishing || isDestroyed || !status.isAttachedToWindow || !adminForeground || epoch != adminUiEpoch || controller.isDriving || controller.dataSuspended) return@runOnUiThread
            result.fold(done) { status.text = it.message ?: "인증을 완료하지 못했습니다" }
        }
    }
}
