package ai.chagok.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 안드로이드 기본 TTS 로 읽고, 듣기는 [typedInput] (화면 입력)으로 대신한다.
 * 시연 모드의 기본이자, 차량 음성이 안 될 때의 대체 수단이다.
 */
open class AndroidVoiceIo(
    context: Context,
    /** 말한 내용을 화면에서 입력받는다. 입력 없이 닫으면 null */
    private val typedInput: () -> ((String, (String?) -> Unit) -> Unit)?,
) : VoiceIo {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = HashMap<String, () -> Unit>()
    private var seq = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale.KOREAN
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
        })
    }

    override val engine: String get() = "안드로이드 음성 · 화면 입력"
    override val hearsVoice: Boolean get() = false

    private fun finish(id: String?) {
        val cb = synchronized(pending) { pending.remove(id) } ?: return
        handler.post { cb() }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val t = tts
        if (!ready || t == null) {
            handler.post { onDone() }
            return
        }
        val id = "u${++seq}"
        synchronized(pending) { pending[id] = onDone }
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
    }

    override fun listen(question: String, onResult: (String?) -> Unit) {
        val input = typedInput()
        if (input == null) handler.post { onResult(null) } else input(question, onResult)
    }

    override fun cancel() {
        synchronized(pending) { pending.clear() }
        tts?.stop()
    }

    override fun shutdown() {
        cancel()
        tts?.shutdown()
        tts = null
    }
}
