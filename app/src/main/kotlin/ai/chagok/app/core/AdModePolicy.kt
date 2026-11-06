package ai.chagok.app.core

/**
 * 광고 모드 상태 기계 (순수 로직, 시계·속도를 밖에서 받는다).
 * 광고는 운행 기록·해시체인·세무 산출물과 분리되어 있어 여기서는 어떤 기록도 만들지 않는다.
 *
 * - NORMAL → AD: 광고 모드가 켜져 있고 차속 > 0 이 확인되면 즉시 한 번
 * - AD → NORMAL: 자동으로 돌아가지 않는다. 운전자가 화면의 버튼으로 [dismiss] 할 때만 복귀하며
 *   (정차 여부와 무관), 이때 다음 광고 이미지로 바꾼다. 같은 주행 중에는 광고를 다시 띄우지 않고
 *   차속 0 이 한 번 확인된 뒤 다시 출발할 때부터 띄운다
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

    /** 운전자가 버튼으로 돌아간 뒤, 정차를 한 번 거칠 때까지 광고를 띄우지 않는다 */
    private var dismissed = false

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) { toNormal(); dismissed = false }
    }

    /** [kmh] 가 null 이면 측정 불가 */
    fun onSpeed(kmh: Double?, now: Long) {
        speedKmh = kmh
        speedAt = now
        evaluate(now)
    }

    /** 버튼으로 앱 화면에 돌아간다. 다음 광고 이미지를 준비하고, 이번 주행 중에는 다시 띄우지 않는다 */
    fun dismiss(): Boolean {
        if (screen != Screen.AD) return false
        toNormal()
        advance()
        dismissed = true
        return true
    }

    /** 1초 주기 등으로 불러, 속도 갱신이 끊겼거나 정차 시간이 찼는지 확인한다 */
    fun tick(now: Long) = evaluate(now)

    private fun evaluate(now: Long) {
        if (!enabled) { toNormal(); return }
        val v = speedKmh
        if (v == null || now - speedAt > STALE_MS) { toNormal(); return }
        if (v > 0.0) {
            if (!dismissed) screen = Screen.AD
        } else dismissed = false
    }

    private fun toNormal() {
        screen = Screen.NORMAL
    }

    private fun advance() {
        if (creativeCount > 0) creative = (creative + 1) % creativeCount
    }

    companion object {
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
