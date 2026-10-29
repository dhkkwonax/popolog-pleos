package ai.chagok.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEvents
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 차량의 Pleos TTS·STT 로 말하고 듣는다. 초기화에 실패하거나 서비스가 없으면 그 부분만 [fallback]
 * (안드로이드 TTS, 화면 입력)으로 대신한다. 결과 콜백은 메인 스레드에서 부른다.
 */
class PleosVoiceIo(context: Context, private val fallback: AndroidVoiceIo) : VoiceIo {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, TtsMode.HYBRID)
    private val stt = SpeechToText(context.applicationContext, SttMode.HYBRID)

    @Volatile private var ttsReady = false
    @Volatile private var sttReady = false
    private var onSpoken: (() -> Unit)? = null
    private var onHeard: ((String?) -> Unit)? = null
    private var lastText = ""

    init {
        registerCredentials()
        runCatching {
            // Pleos TTS 는 initialize() 가 끝난 뒤에야 리스너를 받는다
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    runCatching {
                        tts.addEventListener(object : TtsEvents {
                            override fun onStart() = Unit
                            override fun onReady() = Unit
                            override fun onDone() = spoken()
                            override fun onError(error: TtsError) {
                                Log.w(TAG, "tts error $error")
                                // 엔진이 없거나 인증이 안 된 경우는 다시 해 봐야 소용없으니 안드로이드 음성으로 넘어간다
                                if (error.name in UNUSABLE) ttsReady = false
                                spoken()
                            }
                            override fun onStop() = Unit
                            override fun onUpdatedRms(rms: Double) = Unit
                        })
                        tts.setLocale(Locale.KOREAN)
                        ttsReady = true
                        Log.i(TAG, "tts ready")
                    }.onFailure { Log.w(TAG, "tts listener failed", it) }
                }

                override fun onFailure(error: TtsError) {
                    ttsReady = false
                    Log.w(TAG, "tts init failed $error")
                }
            })
        }.onFailure { Log.w(TAG, "tts unavailable", it) }
        runCatching {
            stt.addListener(object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) {
                    if (text.isNotBlank()) lastText = text
                    if (isFinal) main.post { heard(text.ifBlank { null }) }
                }
                override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
                override fun onStartedRecognition() = Unit
                override fun onEndedRecognition() {
                    // 최종 결과 없이 끝났으면 마지막으로 받은 부분 결과를 쓴다
                    main.post { if (onHeard != null) heard(lastText.ifBlank { null }) }
                }
                override fun onError(error: SttError) {
                    Log.w(TAG, "stt error $error")
                    if (error.name in UNUSABLE) sttReady = false
                    main.post { heard(null) }
                }
                override fun onReady() = Unit
                override fun onUpdatedRms(rms: Double) = Unit
            })
            stt.initialize(object : SttInit {
                override fun onSuccess() {
                    sttReady = true
                    runCatching { stt.setLocale(Locale.KOREAN) }
                    Log.i(TAG, "stt ready")
                }
                override fun onFailure(error: SttError) {
                    sttReady = false
                    Log.w(TAG, "stt init failed $error")
                }
            })
        }.onFailure { Log.w(TAG, "stt unavailable", it) }
    }

    /**
     * Pleos 서버 음성은 앱 인증 정보(Playground 프로젝트의 client ID·secret)가 있어야 한다.
     * gradle 속성 `pleos.clientId`, `pleos.clientSecret` 로 주면 여기서 등록한다. 없으면 기기 내 엔진만 쓴다.
     * 인증 정보가 없어 이 경로는 직접 확인하지 못했다.
     */
    private fun registerCredentials() {
        val id = ai.chagok.app.BuildConfig.PLEOS_CLIENT_ID
        val secret = ai.chagok.app.BuildConfig.PLEOS_CLIENT_SECRET
        if (id.isBlank() || secret.isBlank()) return
        runCatching {
            tts.registerApp(id, secret, object : ai.pleos.playground.tts.listener.OnServerConnectionListener {
                override fun onConnected() { Log.i(TAG, "tts server connected") }
                override fun onFailed(error: TtsError) { Log.w(TAG, "tts server failed $error") }
            })
            stt.registerApp(id, secret, object : ai.pleos.playground.stt.listener.OnServerConnectionListener {
                override fun onConnected() { Log.i(TAG, "stt server connected") }
                override fun onFailed(error: SttError) { Log.w(TAG, "stt server failed $error") }
            })
        }.onFailure { Log.w(TAG, "register failed", it) }
    }

    override val engine: String
        get() = when {
            sttReady && ttsReady -> "Pleos 음성 (STT·TTS)"
            ttsReady -> "Pleos 음성 읽기 · 화면 입력"
            else -> fallback.engine
        }

    override val hearsVoice: Boolean get() = sttReady

    private fun spoken() {
        val cb = onSpoken
        onSpoken = null
        if (cb != null) main.post { cb() }
    }

    private fun heard(text: String?) {
        val cb = onHeard ?: return
        onHeard = null
        cb(text)
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (!ttsReady) {
            fallback.speak(text, onDone)
            return
        }
        onSpoken = onDone
        runCatching { tts.speak(text) }.onFailure {
            Log.w(TAG, "tts speak failed", it)
            spoken()
        }
    }

    override fun listen(question: String, onResult: (String?) -> Unit) {
        if (!sttReady) {
            fallback.listen(question, onResult)
            return
        }
        lastText = ""
        onHeard = onResult
        runCatching { stt.request() }.onFailure {
            Log.w(TAG, "stt request failed", it)
            heard(null)
        }
    }

    override fun cancel() {
        onSpoken = null
        onHeard = null
        runCatching { tts.stop() }
        if (sttReady) runCatching { stt.stop() }
        fallback.cancel()
    }

    override fun shutdown() {
        cancel()
        runCatching { tts.release() }
        runCatching { stt.release() }
        fallback.shutdown()
    }

    private companion object {
        const val TAG = "PopologVoice"

        /** 이 오류가 나면 이 환경에서는 쓸 수 없는 것으로 보고 대체 수단으로 넘어간다 */
        val UNUSABLE = setOf(
            "ON_DEVICE_ENGINE_ERROR", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_SUPPORTED_API", "UNAVAILABLE_LANGUAGE", "INVALID_REQUEST_ERROR",
        )
    }
}
