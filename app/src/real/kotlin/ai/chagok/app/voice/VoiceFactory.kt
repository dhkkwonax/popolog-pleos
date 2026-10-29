package ai.chagok.app.voice

import android.content.Context

/** 실제 동작 모드: Pleos TTS·STT. 서비스가 없으면 안드로이드 TTS 와 화면 입력으로 대신한다 */
object VoiceFactory {
    fun create(context: Context, typedInput: () -> ((String, (String?) -> Unit) -> Unit)?): VoiceIo {
        val fallback = AndroidVoiceIo(context, typedInput)
        return runCatching { PleosVoiceIo(context, fallback) }.getOrElse { fallback }
    }
}
