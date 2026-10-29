package ai.chagok.app.core

import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory

/** 주행거리 기준 교체 주기. 마지막으로 한 계기판 값에서 [intervalKm] 가 지나면 시기가 된 것으로 본다 */
data class MaintItem(val name: String, val intervalKm: Int)

object MaintenancePlan {
    /** 처음 보이는 기본 주기. 설정에서 바꿀 수 있다 */
    val DEFAULT = listOf(
        MaintItem("엔진오일", 10_000),
        MaintItem("브레이크 패드", 30_000),
        MaintItem("타이어", 50_000),
    )

    /** 정비 입력 화면의 항목 칩 */
    val CHIPS = listOf("엔진오일", "타이어", "브레이크", "기타")

    fun encode(items: List<MaintItem>) = items.joinToString("\n") { "${it.name.replace('\t', ' ')}\t${it.intervalKm}" }

    fun decode(s: String): List<MaintItem> =
        s.lines().mapNotNull { l ->
            val f = l.split('\t')
            val km = f.getOrNull(1)?.toIntOrNull()
            if (f.size < 2 || f[0].isBlank() || km == null || km <= 0) null else MaintItem(f[0], km)
        }.ifEmpty { DEFAULT }

    /** 항목 이름이 정비 기록(메모)에 들어 있는지. "브레이크 패드"는 칩 "브레이크"로도 맞춘다 */
    fun mentions(memo: String, item: String): Boolean {
        val head = item.substringBefore(' ')
        return memo.contains(item) || (head.length >= 2 && memo.contains(head))
    }

    /** 항목을 마지막으로 한 계기판 값. 정비 기록 중 계기판이 있는 가장 최근 것. 없으면 null */
    fun lastDoneOdo(item: String, expenses: List<Expense>): Double? =
        expenses.filter { it.category == ExpenseCategory.MAINTENANCE && it.odoKm != null && !it.pendingAmount && mentions(it.memo, item) }
            .maxByOrNull { it.time }?.odoKm

    data class Due(val item: MaintItem, val lastOdo: Double?, /** 주기를 넘긴 거리(km). 음수면 아직 남음 */ val overKm: Double)

    /**
     * 시기가 된 항목. [lastOdoOf] 가 null 이면 이 차의 처음(계기판 0)부터 센다.
     * [leadKm] 만큼 앞서 알려 준다(주기 도래 전 미리).
     */
    fun due(items: List<MaintItem>, odo: Double, lastOdoOf: (String) -> Double?, leadKm: Double = 0.0): List<Due> =
        items.mapNotNull { it ->
            val last = lastOdoOf(it.name)
            val over = odo - (last ?: 0.0) - it.intervalKm
            if (over >= -leadKm) Due(it, last, over) else null
        }.sortedByDescending { it.overKm }
}
