package ai.chagok.app.core

/**
 * 광고 모드 상태 기계 (순수 로직, 시계·속도를 밖에서 받는다).
 * 광고는 운행 기록·해시체인·세무 산출물과 분리되어 있어 여기서는 어떤 기록도 만들지 않는다.
 *
 * - NORMAL → AD: 광고 모드가 켜져 있고 차속 > 0 이 확인되면 즉시 한 번
 * - AD → NORMAL: 자동으로 돌아가지 않는다. 차속 0 이 [STOP_HOLD_MS] 연속되어 [canDismiss] 가 된 뒤
 *   운전자가 버튼으로 [dismiss] 할 때만 복귀하고, 이때 다음 광고 이미지로 바꾼다
 * - 차속을 모르거나 [STALE_MS] 넘게 갱신이 없으면 광고를 띄우지 않는다 (안전 측)
 */
class AdModePolicy(private val creativeCount: Int) {
    enum class Screen { NORMAL, AD }

    var screen = Screen.NORMAL
        private set

    /** 지금(또는 다음에) 보여 줄 광고 이미지 번호 */
    var creative = 0
        private set

    private var enabled = false
    private var speedKmh: Double? = null
    private var speedAt = 0L
    private var stoppedSince = -1L

    /** 정차가 [STOP_HOLD_MS] 이상 이어져 "기록 화면으로" 버튼을 보여 줄 때 */
    var canDismiss = false
        private set

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) toNormal()
    }

    /** [kmh] 가 null 이면 측정 불가 */
    fun onSpeed(kmh: Double?, now: Long) {
        speedKmh = kmh
        speedAt = now
        evaluate(now)
    }

    /** 버튼으로 기록 화면에 돌아간다. 정차 시간이 차지 않았으면 무시한다 */
    fun dismiss(): Boolean {
        if (screen != Screen.AD || !canDismiss) return false
        toNormal()
        advance()
        return true
    }

    /** 1초 주기 등으로 불러, 속도 갱신이 끊겼거나 정차 시간이 찼는지 확인한다 */
    fun tick(now: Long) = evaluate(now)

    private fun evaluate(now: Long) {
        if (!enabled) { toNormal(); return }
        val v = speedKmh
        if (v == null || now - speedAt > STALE_MS) { toNormal(); return }
        if (v > 0.0) {
            stoppedSince = -1L
            canDismiss = false
            screen = Screen.AD
            return
        }
        if (screen == Screen.AD) {
            if (stoppedSince < 0) stoppedSince = now
            canDismiss = now - stoppedSince >= STOP_HOLD_MS
        } else stoppedSince = -1L
    }

    private fun toNormal() {
        screen = Screen.NORMAL
        stoppedSince = -1L
        canDismiss = false
    }

    private fun advance() {
        if (creativeCount > 0) creative = (creative + 1) % creativeCount
    }

    companion object {
        /** 허용 범위 3~5초의 가운데 */
        const val STOP_HOLD_MS = 4_000L
        const val STALE_MS = 5_000L
    }
}

/** 소리 광고 하루 [DAILY_LIMIT] 회 이하. 날짜가 바뀌면 처음부터 센다 */
class AdVoiceBudget(private val day: String, private val used: Int) {
    val remaining: Int get() = (DAILY_LIMIT - used).coerceAtLeast(0)
    val canOffer: Boolean get() = remaining > 0

    companion object {
        const val DAILY_LIMIT = 2

        /** 저장된 "날짜|횟수"를 오늘 기준으로 읽는다. 날짜가 다르거나 깨졌으면 0회 */
        fun parse(stored: String, today: String): AdVoiceBudget {
            val p = stored.split('|')
            val n = if (p.size == 2 && p[0] == today) p[1].toIntOrNull()?.coerceAtLeast(0) ?: 0 else 0
            return AdVoiceBudget(today, n)
        }

        fun afterOffer(stored: String, today: String): String {
            val b = parse(stored, today)
            return "$today|${DAILY_LIMIT - b.remaining + 1}"
        }
    }
}

/** 음성 해제 명령 */
object AdCommand {
    private val STOP = Regex("광고\\s*(그만|꺼|끄)")
    fun isStop(said: String): Boolean = STOP.containsMatchIn(said.trim())
}
