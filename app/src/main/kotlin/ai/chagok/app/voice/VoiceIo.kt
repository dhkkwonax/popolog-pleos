package ai.chagok.app.voice

/**
 * 말하기(TTS)와 듣기(STT). 차량에서는 Pleos TTS·STT, 시연 모드는 안드로이드 TTS와 화면 입력으로 대신한다.
 * 콜백은 모두 메인 스레드에서 불린다.
 */
interface VoiceIo {
    /** 화면에 보여 줄 방식 이름 */
    val engine: String

    /** 마이크로 직접 듣는 방식인지. false 면 [listen] 이 화면 입력으로 대신한다 */
    val hearsVoice: Boolean

    /** 읽어 주고 끝나면 [onDone]. 읽지 못해도 [onDone] 은 불린다 */
    fun speak(text: String, onDone: () -> Unit = {})

    /** 한 번 듣는다. 못 들었거나 실패하면 null. 이전 듣기는 취소된다 */
    fun listen(question: String, onResult: (String?) -> Unit)

    /** 읽기·듣기를 멈춘다 (결과 콜백은 부르지 않는다) */
    fun cancel()

    fun shutdown()
}
