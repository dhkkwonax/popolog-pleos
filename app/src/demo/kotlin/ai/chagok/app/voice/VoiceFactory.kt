package ai.chagok.app.voice

import android.content.Context

/** 데모 모드: 안드로이드 TTS 로 읽고, 듣기는 화면 입력(시연 응답)으로 대신한다 */
object VoiceFactory {
    fun create(context: Context, typedInput: () -> ((String, (String?) -> Unit) -> Unit)?): VoiceIo =
        AndroidVoiceIo(context, typedInput)
}
