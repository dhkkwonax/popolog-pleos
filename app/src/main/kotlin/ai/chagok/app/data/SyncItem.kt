package ai.chagok.app.data

import ai.chagok.app.model.ExpenseCategory

/** 외부 연동에서 들어온 비용 내역. 사용자가 승인하기 전에는 장부(비용)에 넣지 않는다 */
data class SyncItem(
    val id: Long = 0,
    /** 연동 출처가 정한 고유 번호. 같은 번호는 한 번만 받는다 */
    val externalId: String,
    val time: Long,
    val category: ExpenseCategory,
    val amountWon: Long,
    val shop: String = "",
    val odoKm: Double? = null,
    val memo: String = "",
    val state: String = REVIEW,
) {
    companion object {
        const val REVIEW = "REVIEW"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
    }
}
