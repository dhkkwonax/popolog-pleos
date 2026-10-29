package ai.chagok.app.core

import ai.chagok.app.data.SyncItem
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory

/** 외부(정비소·주차장·하이패스 등)에서 받은 비용 한 건 */
data class RawRecord(
    val externalId: String,
    val time: Long,
    val category: ExpenseCategory,
    val amountWon: Long,
    val shop: String = "",
    val odoKm: Double? = null,
    val memo: String = "",
)

/**
 * 비용 내역을 가져오는 곳. 실제 연동 경로가 확인되면 이 인터페이스를 구현한 어댑터만 바꿔 끼운다.
 * 가져온 내역은 바로 장부에 들어가지 않고 '검토 필요'로 보관한다.
 */
interface ExpenseSource {
    val name: String
    fun fetchSince(lastSyncAt: Long): List<RawRecord>
}

/** 실제 동작 모드에서 아직 연동 경로가 없을 때: 아무것도 가져오지 않는다 */
object NoExpenseSource : ExpenseSource {
    override val name = "연동 없음"
    override fun fetchSince(lastSyncAt: Long): List<RawRecord> = emptyList()
}

/** 연동 경로가 없는 지금, 흐름(검토·승인·중복 제거)을 보여 주기 위한 샘플 데이터 소스 */
class SampleExpenseSource(private val now: () -> Long) : ExpenseSource {
    override val name = "샘플 연동 (가정)"

    override fun fetchSince(lastSyncAt: Long): List<RawRecord> {
        val t = now()
        val day = 86_400_000L
        return listOf(
            RawRecord("SAMPLE-0001", t - 2 * day, ExpenseCategory.MAINTENANCE, 85_000, "스피드메이트 판교점", memo = "엔진오일, 오일필터"),
            RawRecord("SAMPLE-0002", t - day, ExpenseCategory.PARKING, 6_000, "판교역 공영주차장"),
            RawRecord("SAMPLE-0003", t - day / 2, ExpenseCategory.TOLL, 4_800, "경부고속도로 하이패스"),
        ).filter { it.time > lastSyncAt }
    }
}

object ExpenseSync {
    /** 날짜(±1일)·항목·금액이 같은 기존 기록. 같은 내역을 직접 입력해 둔 경우를 알려 주려는 것 */
    fun findSimilar(item: SyncItem, existing: List<Expense>): Expense? =
        existing.firstOrNull {
            !it.pendingAmount && it.category == item.category && it.amountWon == item.amountWon &&
                kotlin.math.abs(it.time - item.time) <= 86_400_000L
        }

    fun toExpense(item: SyncItem, odoFallback: Double?): Expense = Expense(
        time = item.time, category = item.category, amountWon = item.amountWon,
        odoKm = item.odoKm ?: odoFallback, memo = item.memo, source = ai.chagok.app.model.ExpenseSource.SYNC, shop = item.shop,
    )

    fun toItem(r: RawRecord) = SyncItem(
        externalId = r.externalId, time = r.time, category = r.category, amountWon = r.amountWon,
        shop = r.shop, odoKm = r.odoKm, memo = r.memo,
    )
}
