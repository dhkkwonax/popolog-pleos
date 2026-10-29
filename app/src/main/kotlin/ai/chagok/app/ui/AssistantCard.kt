package ai.chagok.app.ui

import ai.chagok.app.MainActivity
import ai.chagok.app.voice.FreeKind
import ai.chagok.app.voice.Prompt
import android.view.View

/**
 * 앱이 먼저 묻는 질문 카드: 질문, 음성 상태, 터치로 답하는 보기. 말로 답하지 못하는 환경에서도 같은 질문에 버튼으로 답한다.
 */
fun MainActivity.assistantCard(p: Prompt): View {
    val c = controller
    return card(C.SURFACE, stroke = C.ACCENT) {
        add(hbox {
            add(icon(Ic.MIC, C.ACCENT, 28), WRAP, WRAP, end = 16)
            add(vbox {
                add(text(p.question, 22f, bold = true))
                add(text(c.assistant.status + " · " + c.voice.engine, 15f, C.SUB), top = 6)
                if (p.hint.isNotEmpty()) add(text(p.hint, 14f, C.DIM), top = 2)
            }, 0, WRAP, 1f)
        })
        if (p.chips.isNotEmpty()) {
            val buttons = p.chips.map { chip -> button(chip.label, BtnStyle.SECONDARY) { c.assistant.answerChip(chip.value) } }
            add(grid(if (isNarrow) 2 else minOf(4, buttons.size), buttons, gapDp = 10), top = 16)
        }
        add(hbox {
            add(button("말하기", BtnStyle.PRIMARY, icon = Ic.MIC) { c.assistant.listenAgain() }, 0, WRAP, 1f, end = 10)
            when (p.free) {
                FreeKind.AMOUNT -> add(button("직접 입력") { askAmountPad(p.question, p.hint) { v -> c.assistant.answerText("${v}원") } }, 0, WRAP, 1f, end = 10)
                FreeKind.NAME -> add(button("직접 입력") { askTextInput(p.question, p.hint) { v -> c.assistant.answerText(v) } }, 0, WRAP, 1f, end = 10)
                FreeKind.NONE -> Unit
            }
            add(button("나중에", BtnStyle.GHOST) { c.assistant.later() }, 0, WRAP, 0.8f)
        }, top = 14)
    }
}
