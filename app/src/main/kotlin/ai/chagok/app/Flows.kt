package ai.chagok.app

import ai.chagok.app.core.AnswerParser
import ai.chagok.app.core.ArrivalPolicy
import ai.chagok.app.core.ExpenseSync
import ai.chagok.app.core.ExpenseSource as SyncSource
import ai.chagok.app.core.Geo
import ai.chagok.app.core.KoreanAmountParser
import ai.chagok.app.core.MaintItem
import ai.chagok.app.core.MaintenancePlan
import ai.chagok.app.core.TripClassifier
import ai.chagok.app.data.SyncItem
import ai.chagok.app.model.ClassifiedBy
import ai.chagok.app.model.Driver
import ai.chagok.app.model.DriverMethod
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.ExpenseDraft
import ai.chagok.app.model.ExpenseSource
import ai.chagok.app.model.Place
import ai.chagok.app.model.PlaceKind
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import ai.chagok.app.nav.Arrival
import ai.chagok.app.nav.PlaceCandidate
import ai.chagok.app.voice.Chip
import ai.chagok.app.voice.FreeKind
import ai.chagok.app.voice.Prompt

/**
 * 앱이 먼저 묻는 대화 흐름들: 도착한 곳 저장, 운행 목적, 주유·충전 금액, 운전자 확인, 정비 시기.
 * 모두 [AppController.assistant] 를 거치므로 음성으로 답하거나 같은 질문의 버튼으로 답한다.
 */
class Flows(private val c: AppController) {
    private val sessionDeclined = mutableListOf<ArrivalPolicy.Point>()

    /** 내비의 즐겨찾기·최근 목적지 (장소 이름 후보) */
    var candidates: List<PlaceCandidate> = emptyList()

    /** 정비 입력 화면을 여는 UI 훅 (MainActivity 가 정한다) */
    var openExpenseInput: ((ExpenseDraft) -> Unit)? = null

    // ---- 음성 점검 ----

    /** 설정의 '음성 점검': 말하기와 듣기가 되는지 한 번 확인한다. 들은 말은 화면에 그대로 보여 준다 */
    fun voiceCheck(onResult: (String) -> Unit) {
        if (c.dataSuspended) return
        c.assistant.ask(
            Prompt(
                id = "voice_check", question = "음성 점검이에요. 소리가 들리면 네, 라고 말씀해 주세요.",
                chips = listOf(Chip("네", "yes"), Chip("아니오", "no")),
                match = { said ->
                    onResult("들은 말: \"$said\"")
                    AnswerParser.yesNo(said)?.let { y -> if (y) "yes" else "no" }
                },
                onChip = { v, voice -> onResult(if (voice) "음성으로 \"$v\" 인식" else "버튼으로 답함") },
            ),
        )
    }

    // ---- 도착한 곳 저장 ----

    fun onArrived(a: Arrival) {
        if (c.dataSuspended) return
        val lat = a.lat ?: c.lat
        val lng = a.lng ?: c.lng
        val declined = ArrivalPolicy.decode(c.db.setting(KEY_DECLINED)) + sessionDeclined
        if (!ArrivalPolicy.shouldAsk(lat, lng, c.places, declined)) return
        val label = if (a.kind == Arrival.Kind.WAYPOINT) "경유지" else "목적지"
        val where = a.name.ifBlank { label }
        c.assistant.ask(
            Prompt(
                // 위치마다 따로 묻는다 (경유지와 목적지가 연이어 도착해도 하나가 사라지지 않게)
                id = "place_save:${String.format(java.util.Locale.US, "%.4f,%.4f", lat, lng)}", question = "${where}에 도착했어요. 이 위치를 저장할까요?",
                chips = listOf(Chip("저장", "yes"), Chip("저장 안 함", "no"), Chip("다시 묻지 않기", "never")),
                match = AnswerParser::saveChoice,
                onChip = { v, _ ->
                    when (v) {
                        "yes" -> askKind(a, lat, lng)
                        // 저장 안 함: 아무것도 남기지 않는다. 같은 위치는 이번 실행 동안만 다시 묻지 않는다
                        "no" -> sessionDeclined += ArrivalPolicy.Point(lat, lng)
                        // 다시 묻지 않기: 사용자가 고른 경우에만 그 위치를 기기에 적어 둔다
                        "never" -> c.db.setSetting(KEY_DECLINED, ArrivalPolicy.encode(ArrivalPolicy.decode(c.db.setting(KEY_DECLINED)) + ArrivalPolicy.Point(lat, lng)))
                    }
                },
            ),
        )
    }

    private fun askKind(a: Arrival, lat: Double, lng: Double) {
        c.assistant.ask(
            Prompt(
                id = "place_kind", question = "무엇으로 저장할까요? 거래처, 집, 회사, 기타 중에 말씀해 주세요.",
                chips = PlaceKind.values().map { Chip(it.label, it.name) },
                match = { AnswerParser.placeKind(it)?.name },
                onChip = { v, _ -> askName(a, lat, lng, PlaceKind.valueOf(v)) },
            ),
            front = true,
        )
    }

    private fun askName(a: Arrival, lat: Double, lng: Double, kind: PlaceKind) {
        val near = candidates.filter { Geo.distanceM(lat, lng, it.lat, it.lng) <= CANDIDATE_RADIUS_M }
        val names = (listOfNotNull(a.name.takeIf { it.isNotBlank() }) + near.map { it.name } +
            listOf(kind.label).filter { kind == PlaceKind.HOME || kind == PlaceKind.OFFICE }).distinct().take(4)
        fun save(name: String) {
            val addr = a.address.ifBlank { near.firstOrNull { it.name == name }?.address.orEmpty() }
            c.savePlace(Place(name = name, kind = kind, lat = lat, lng = lng, radiusM = DEFAULT_RADIUS_M, address = addr))
            c.speak("${name}을 ${kind.label}으로 저장했어요.")
            reclassify()
        }
        c.assistant.ask(
            Prompt(
                id = "place_name", question = "이름을 말씀해 주세요.",
                chips = names.map { Chip(it, it) }, free = FreeKind.NAME, hint = "예: 한빛물산",
                onChip = { v, _ -> save(v) },
                onText = { said ->
                    val n = said.trim().trim('.', '!', '?')
                    if (n.isEmpty()) false else { save(n); true }
                },
            ),
            front = true,
        )
    }

    /** 방금 저장한 장소로 미분류 운행을 다시 분류한다 (저장한 장소는 목적 자동 분류에 쓰인다) */
    private fun reclassify() {
        for (t in c.trips.filter { it.purpose == TripPurpose.UNCLASSIFIED }) {
            val r = TripClassifier.classify(t.startLat, t.startLng, t.endLat, t.endLng, c.places)
            if (r.purpose != TripPurpose.UNCLASSIFIED) {
                c.assistant.drop("purpose:${t.id}")
                c.classifyTrip(t.id, r.purpose, ClassifiedBy.PLACE)
            }
        }
    }

    // ---- 운행 목적 ----

    fun askPurpose(t: Trip) {
        if (!c.canEditTrip(t.id)) return
        if (c.dataSuspended) return
        val route = when {
            t.fromName.isNotBlank() && t.toName.isNotBlank() -> "${t.fromName}에서 ${t.toName}까지"
            t.fromName.isNotBlank() -> "${t.fromName}에서 출발했어요"
            t.toName.isNotBlank() -> "${t.toName}까지"
            else -> ""
        }
        c.assistant.ask(
            Prompt(
                id = "purpose:${t.id}",
                question = if (t.memo == AppController.GAP_MEMO) {
                    "앱이 꺼진 동안 ${AppController.fmtKmSpoken(t.distanceKm)} 운행했어요. 업무 운행이었나요?"
                } else {
                    "${AppController.fmtKmSpoken(t.distanceKm)} 운행을 기록했어요. " + (if (route.isNotEmpty()) "$route. " else "") + "업무 운행이었나요?"
                },
                chips = listOf(Chip("일반업무", TripPurpose.BUSINESS.name), Chip("출퇴근", TripPurpose.COMMUTE.name), Chip("비업무", TripPurpose.PERSONAL.name)),
                match = { AnswerParser.purpose(it)?.name }, refType = "trip", refId = t.id,
                onChip = { v, voice -> c.classifyTrip(t.id, TripPurpose.valueOf(v), if (voice) ClassifiedBy.VOICE else ClassifiedBy.MANUAL) },
            ),
        )
    }

    // ---- 주유·충전 금액 ----

    fun askAmount(e: Expense) {
        if (c.dataSuspended) return
        c.assistant.ask(
            Prompt(
                // "주유가" / "충전이" — 받침 유무로 조사를 고른다
                id = "amount:${e.id}", question = "${if (e.category == ExpenseCategory.CHARGE) "충전이" else "주유가"} 감지됐어요. 결제 금액을 말씀해 주세요.",
                free = FreeKind.AMOUNT, hint = "예: 칠만 삼천 원", refType = "expense", refId = e.id,
                onText = { said ->
                    val amount = KoreanAmountParser.parse(said)
                    if (amount == null) false else { confirmAmount(e.id, amount); true }
                },
            ),
        )
    }

    private fun confirmAmount(id: Long, amount: Long) {
        c.assistant.ask(
            Prompt(
                id = "amount_ok:$id", question = "${AppController.amountSpoken(amount)} 맞나요?",
                chips = listOf(Chip("맞아요", "yes"), Chip("다시", "no")), match = { AnswerParser.yesNo(it)?.let { y -> if (y) "yes" else "no" } },
                refType = "expense", refId = id,
                onChip = { v, voice ->
                    if (v == "yes") c.setExpenseAmount(id, amount, if (voice) ExpenseSource.VOICE else ExpenseSource.MANUAL)
                    else c.expenses.firstOrNull { it.id == id }?.let { askAmount(it) }
                },
            ),
            front = true,
        )
    }

    // ---- 운전자 확인 ----

    fun confirmDriver(name: String, method: DriverMethod) {
        if (c.dataSuspended) return
        if (method == DriverMethod.MANUAL) return
        c.assistant.ask(
            Prompt(
                id = "driver_ok", question = "${name}님으로 인식했어요. 맞으세요?",
                chips = listOf(Chip("맞아요", "yes"), Chip("아니에요", "no")), match = { AnswerParser.yesNo(it)?.let { y -> if (y) "yes" else "no" } },
                onChip = { v, _ -> if (v == "no") askWho() },
            ),
            front = true,
        )
    }

    private fun askWho() {
        if (c.drivers.isEmpty()) return
        c.assistant.ask(
            Prompt(
                id = "driver_who", question = "누구세요?",
                chips = c.drivers.map { Chip(it.name, it.id) }, match = { AnswerParser.driver(it, c.drivers)?.id },
                onChip = { id, _ -> c.chooseDriver(id) },
            ),
            front = true,
        )
    }

    // ---- 정비 시기 ----

    fun plan(): List<MaintItem> = MaintenancePlan.decode(c.db.setting(KEY_PLAN))

    fun savePlan(items: List<MaintItem>) { if (!c.dataSuspended) c.db.setSetting(KEY_PLAN, MaintenancePlan.encode(items)) }

    /** 항목을 마지막으로 한 계기판 값 (정비 기록에서) */
    fun lastDoneOdo(item: String): Double? = MaintenancePlan.lastDoneOdo(item, c.expenses)

    fun dueItems(): List<MaintenancePlan.Due> = MaintenancePlan.due(plan(), c.odoKm, ::lastDoneOdo)

    /** 정차 중에 주기가 된 항목을 한 번 알린다 ("정비했어요"로 입력과 이어짐) */
    fun checkMaintenance() {
        if (c.dataSuspended) return
        if (c.isDriving || c.odoKm <= 0) return
        val d = dueItems().firstOrNull { (c.db.setting(KEY_NOTIFIED_PREFIX + it.item.name).toDoubleOrNull() ?: -1e9) < c.odoKm - RENOTIFY_KM } ?: return
        c.db.setSetting(KEY_NOTIFIED_PREFIX + d.item.name, c.odoKm.toString())
        c.assistant.ask(
            Prompt(
                id = "maint:${d.item.name}", question = "${d.item.name} 교체 시기가 됐어요. 정비하셨나요?",
                chips = listOf(Chip("정비했어요", "yes")), match = { AnswerParser.yesNo(it)?.let { y -> if (y) "yes" else "no" } },
                onChip = { v, _ ->
                    if (v == "yes") openExpenseInput?.invoke(ExpenseDraft(ExpenseCategory.MAINTENANCE, items = listOf(d.item.name.substringBefore(' '))))
                },
            ),
        )
    }

    /** 정비 기록을 저장하면 그 항목의 알림 표시를 지운다 (다시 주기가 되면 새로 알린다) */
    fun onMaintenanceSaved(memo: String) {
        if (c.dataSuspended) return
        for (it in plan()) {
            if (MaintenancePlan.mentions(memo, it.name)) c.db.setSetting(KEY_NOTIFIED_PREFIX + it.name, "")
        }
    }

    /** 시연: 교체 시기가 가장 가까운 항목의 교체 주기를 지금 막 넘기도록 짧게 줄인다 (설정의 정비 주기에서 되돌릴 수 있다) */
    fun demoMakeDue() {
        if (c.dataSuspended) return
        val items = plan()
        // 방금 정비한 항목은 피해, 마지막 정비 이후 가장 많이 달린 항목을 고른다
        val first = items.maxByOrNull { c.odoKm - (lastDoneOdo(it.name) ?: 0.0) } ?: items.first()
        val gap = c.odoKm - (lastDoneOdo(first.name) ?: 0.0)
        if (gap >= first.intervalKm) {
            c.db.setSetting(KEY_NOTIFIED_PREFIX + first.name, "")
        } else {
            // 500km 단위로 내림해 보기 좋은 주기로 (예: 1,500km)
            val short = maxOf(100, ((gap - 100.0) / 500).toInt() * 500)
            savePlan(items.map { if (it.name == first.name) it.copy(intervalKm = short) else it })
            c.db.setSetting(KEY_NOTIFIED_PREFIX + first.name, "")
        }
        checkMaintenance()
        c.notifyUi()
    }

    // ---- 외부 연동 ----

    data class SyncResult(val added: Int, val duplicates: Int)

    fun sync(source: SyncSource): SyncResult {
        if (c.dataSuspended) return SyncResult(0, 0)
        val raws = source.fetchSince(0L)
        var added = 0
        for (r in raws) if (c.db.addSyncItem(ExpenseSync.toItem(r))) added++
        c.reload()
        c.notifyUi()
        return SyncResult(added, raws.size - added)
    }

    fun syncReview(): List<SyncItem> = c.db.syncItems(SyncItem.REVIEW)

    fun similar(item: SyncItem): Expense? = ExpenseSync.findSimilar(item, c.expenses)

    /** 승인: 장부에 넣는다. [merge] 면 이미 입력한 같은 기록을 두고 연동 내역만 처리 완료로 한다 */
    fun approve(item: SyncItem, merge: Boolean) {
        if (c.dataSuspended) return
        if (!merge) c.saveExpense(ExpenseSync.toExpense(item, c.odoKm.takeIf { it > 0 }))
        c.db.setSyncState(item.id, SyncItem.APPROVED)
        c.notifyUi()
    }

    fun reject(item: SyncItem) {
        if (c.dataSuspended) return
        c.db.setSyncState(item.id, SyncItem.REJECTED)
        c.notifyUi()
    }

    fun recentShops(): List<String> =
        c.expenses.filter { it.shop.isNotBlank() }.sortedByDescending { it.time }.map { it.shop }.distinct().take(5)

    companion object {
        const val KEY_DECLINED = "declined_places"
        const val KEY_PLAN = "maint_plan"
        private const val KEY_NOTIFIED_PREFIX = "maint_notified_"
        private const val RENOTIFY_KM = 500.0
        private const val CANDIDATE_RADIUS_M = 800.0
        private const val DEFAULT_RADIUS_M = 250
    }
}
