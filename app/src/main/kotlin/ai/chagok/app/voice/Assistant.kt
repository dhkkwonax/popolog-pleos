package ai.chagok.app.voice

import android.os.Handler
import android.os.Looper

/** 터치로 고르는 보기. [value] 가 대답으로 전달된다 */
data class Chip(val label: String, val value: String)

enum class FreeKind { NONE, AMOUNT, NAME }

/** 앱이 먼저 묻는 질문 하나 */
class Prompt(
    val id: String,
    val question: String,
    val chips: List<Chip> = emptyList(),
    /** 보기에 없는 말(금액·이름)을 받는지 */
    val free: FreeKind = FreeKind.NONE,
    val hint: String = "",
    /** 질문이 가리키는 기록 (같은 기록을 따로 묻는 카드를 숨기려고) */
    val refType: String = "",
    val refId: Long = 0,
    /** 들은 문장을 보기 값으로 바꾸는 규칙. 못 알아들으면 null */
    val match: ((String) -> String?)? = null,
    val onChip: (value: String, byVoice: Boolean) -> Unit = { _, _ -> },
    /** 자유 문장 대답. 받아들였으면 true */
    val onText: (String) -> Boolean = { true },
    val onLater: () -> Unit = {},
)

/**
 * 대화형 질문 진행기: 앱이 먼저 묻고(TTS) 대답을 듣고(STT) 못 들으면 터치 버튼으로 받는다.
 * 질문은 줄을 세워 하나씩 묻고, 주행 중에는 묻지 않는다([canAsk]).
 */
class Assistant(
    private val voice: () -> VoiceIo,
    /** 지금 물어도 되는지 (정차 중일 때만) */
    private val canAsk: () -> Boolean,
    private val onChanged: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Prompt>()

    var current: Prompt? = null
        private set

    /** 화면에 보여 줄 상태 */
    var status: String = ""
        private set
    var listening = false
        private set
    private var retries = 0
    private var token = 0

    fun ask(p: Prompt, front: Boolean = false) {
        // 같은 질문이 이미 줄에 있으면 중복해서 묻지 않는다
        if (current?.id == p.id || queue.any { it.id == p.id }) return
        if (front) queue.addFirst(p) else queue.addLast(p)
        pump()
    }

    fun isPending(id: String) = current?.id == id || queue.any { it.id == id }

    /** 정차 상태가 바뀔 때 부른다 */
    fun pump() {
        if (current != null || queue.isEmpty() || !canAsk()) return
        val p = queue.removeFirst()
        current = p
        retries = 0
        start(p)
    }

    private fun start(p: Prompt) {
        val my = ++token
        status = "말하는 중"
        listening = false
        onChanged()
        voice().speak(p.question) {
            if (my == token && current === p) listen(p, my, auto = true)
        }
    }

    /**
     * 듣기를 시작한다. 마이크로 듣지 못하는 환경(시연·에뮬레이터)에서는 [auto] 일 때 입력 창을 띄우지 않고
     * 카드의 버튼으로 답하게 한다. '말하기' 버튼을 누르면([auto] false) 그때 화면 입력 창이 열린다.
     */
    private fun listen(p: Prompt, my: Int, auto: Boolean = false) {
        if (auto && !voice().hearsVoice) {
            status = "아래 버튼으로 답하세요"
            listening = false
            onChanged()
            return
        }
        status = if (voice().hearsVoice) "듣는 중 · 말씀하세요" else "아래 버튼으로 답하세요"
        listening = voice().hearsVoice
        onChanged()
        voice().listen(p.question) { said ->
            if (my != token || current !== p) return@listen
            listening = false
            if (said.isNullOrBlank()) {
                status = "아래 버튼으로 답하세요"
                onChanged()
            } else {
                heard(p, said, my)
            }
        }
        // 마이크로 듣는데 결과가 안 오면 터치로 넘긴다
        if (voice().hearsVoice) handler.postDelayed({
            if (my == token && current === p && listening) {
                voice().cancel()
                listening = false
                status = "아래 버튼으로 답하세요"
                onChanged()
            }
        }, LISTEN_TIMEOUT_MS)
    }

    private fun heard(p: Prompt, said: String, my: Int) {
        p.match?.invoke(said)?.let { answerChip(it, byVoice = true); return }
        if (p.free != FreeKind.NONE && p.onText(said)) {
            finish()
            return
        }
        if (retries < 1) {
            retries++
            status = "잘 못 들었어요"
            onChanged()
            voice().speak("잘 못 들었어요. 다시 말씀해 주세요.") {
                if (my == token && current === p) listen(p, my, auto = true)
            }
        } else {
            status = "아래 버튼으로 답하세요"
            onChanged()
        }
    }

    /** 터치(또는 말로 고른) 보기 대답 */
    fun answerChip(value: String, byVoice: Boolean = false) {
        val p = current ?: return
        finish()
        p.onChip(value, byVoice)
    }

    /** 줄에 선 질문(또는 지금 묻는 질문)을 없앤다. 같은 일이 다른 길로 해결됐을 때 */
    fun drop(idPrefix: String) {
        queue.removeAll { it.id.startsWith(idPrefix) }
        if (current?.id?.startsWith(idPrefix) == true) finish()
    }

    /** 직접 입력한 문장 대답. 받아들여지지 않으면 질문은 그대로 남는다 */
    fun answerText(text: String): Boolean {
        val p = current ?: return false
        if (!p.onText(text)) return false
        finish()
        return true
    }

    /** 다시 듣기(말하기 버튼) */
    fun listenAgain() {
        val p = current ?: return
        retries = 0
        voice().cancel()
        listen(p, ++token)
    }

    /** 나중에: 질문을 닫는다 */
    fun later() {
        val p = current ?: return
        finish()
        p.onLater()
    }

    /** 운행이 시작되는 등으로 지금은 물을 수 없을 때: 말하던 것을 멈추고 질문은 줄 맨 앞으로 돌려 놓는다 */
    fun pause() {
        val p = current ?: return
        token++
        voice().cancel()
        current = null
        listening = false
        queue.addFirst(p)
        onChanged()
    }

    fun clear() {
        token++
        voice().cancel()
        current = null
        listening = false
        queue.clear()
        onChanged()
    }

    private fun finish() {
        token++
        voice().cancel()
        current = null
        listening = false
        onChanged()
        // 다음 질문은 한 박자 뒤에 (방금 대답에 이어지는 질문이 맨 앞에 끼어들 수 있게)
        handler.post { pump() }
    }

    companion object {
        const val LISTEN_TIMEOUT_MS = 12_000L
    }
}
