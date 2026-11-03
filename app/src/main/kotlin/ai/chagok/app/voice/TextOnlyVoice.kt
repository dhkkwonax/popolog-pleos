package ai.chagok.app.voice

import android.os.Handler
import android.os.Looper

/** 음성을 쓰지 않기로 한 경우: 말하지도 마이크로 듣지도 않고, 질문은 화면 입력으로만 받는다 */
class TextOnlyVoice(private val typedInput: () -> ((String, (String?) -> Unit) -> Unit)?) : VoiceIo {
    private val handler = Handler(Looper.getMainLooper())

    override val engine: String get() = "음성 사용 안 함 · 화면 입력"
    override val hearsVoice: Boolean get() = false

    override fun speak(text: String, onDone: () -> Unit) {
        handler.post { onDone() }
    }

    override fun listen(question: String, onResult: (String?) -> Unit) {
        val input = typedInput()
        if (input == null) handler.post { onResult(null) } else input(question, onResult)
    }

    override fun cancel() = Unit

    override fun shutdown() = Unit
}
