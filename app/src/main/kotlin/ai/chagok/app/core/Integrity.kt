package ai.chagok.app.core

import ai.chagok.app.model.Expense
import ai.chagok.app.model.Trip
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 운행·비용 기록의 위변조 검증 (해시체인).
 *
 * - 기록이 확정·정정·삭제될 때마다 체인에 한 줄을 덧붙인다 (append-only). 이미 쓴 줄은 고치지 않는다.
 * - 각 줄의 hash = SHA-256(앞 줄 hash + "\n" + 종류 + "\n" + 정규화한 기록 필드). 첫 줄의 앞은 [GENESIS].
 * - 화면에 보이는 운행·비용 표는 "최신 값" 사본이다. 검증은 체인을 처음부터 다시 계산하고,
 *   표의 각 기록이 체인에 남은 그 기록의 마지막 값과 같은지도 본다.
 * - 하루가 끝나면 그날 마지막 hash 를 [DailyAnchor] 로 묶어 외부 타임스탬프(RFC 3161, [Rfc3161])를 받는다.
 *   그 뒤에 체인을 통째로 다시 만들어도 앵커와 맞지 않으므로 드러난다.
 */
object Integrity {
    /** 체인의 시작 값. 바꾸면 이전 체인과 이어지지 않는다 */
    val GENESIS: String = sha256("Popolog ledger genesis v1")

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun hash(prev: String, kind: Kind, payload: String): String = sha256(prev + "\n" + kind.name + "\n" + payload)

    /**
     * 운행 기록의 정규화: 시각, 계기판 km(0.1 단위), 목적, 분류 방법, 운전자, 출발·도착 장소 이름, 메모.
     * v1(머리 "trip")은 메모가 없었다. 이미 쓴 v1 줄은 v1 규칙으로 대조한다 ([matches])
     */
    fun canonical(t: Trip, v: Int = 2): String = (
        listOf(
            if (v == 1) "trip" else "trip2", t.id.toString(), t.startTime.toString(), t.endTime.toString(),
            km(t.startOdoKm), km(t.endOdoKm), t.purpose.name, t.classifiedBy.name, t.driver, t.fromName, t.toName,
        ) + if (v == 1) emptyList() else listOf(t.memo)
        ).joinToString("|") { if (v == 1) it.replace("|", "/") else clean(it) }

    /** 비용 기록의 정규화: 시각, 항목, 금액, 계기판 km, 수량, 입력 방식, 금액 미입력 여부, 메모 (v1 은 메모 없음) */
    fun canonical(e: Expense, v: Int = 2): String = (
        listOf(
            if (v == 1) "expense" else "expense2", e.id.toString(), e.time.toString(), e.category.name, e.amountWon.toString(),
            e.odoKm?.let(::km) ?: "", e.quantity?.let(::km) ?: "", e.source.name, if (e.pendingAmount) "pending" else "",
        ) + if (v == 1) emptyList() else listOf(e.memo)
        ).joinToString("|") { if (v == 1) it.replace("|", "/") else clean(it) }

    /** 체인 줄이 표의 기록과 같은지. 줄이 쓰인 규칙(v1·v2)으로 정규화해 비교한다 */
    fun matches(payload: String, t: Trip) = payload == canonical(t, if (payload.startsWith("trip|")) 1 else 2)
    fun matches(payload: String, e: Expense) = payload == canonical(e, if (payload.startsWith("expense|")) 1 else 2)

    private fun clean(s: String) = s.replace("|", "/").replace("\n", " ")

    private fun km(v: Double) = String.format(Locale.US, "%.1f", v)

    /** 기록 종류. FIX 는 정정 기록, DEL 은 삭제 기록 (원래 줄은 그대로 남는다) */
    enum class Kind(val label: String) {
        TRIP("운행"), TRIP_FIX("운행 정정"), EXPENSE("비용"), EXPENSE_FIX("비용 정정"), EXPENSE_DEL("비용 삭제");

        val isTrip get() = this == TRIP || this == TRIP_FIX
    }

    /** 체인의 한 줄. [seq] 는 1부터 */
    data class Entry(
        val seq: Long,
        val time: Long,
        val kind: Kind,
        /** 운행·비용 표의 id */
        val ref: Long,
        val payload: String,
        val prevHash: String,
        val hash: String,
    )

    /** 다음 줄을 만든다 */
    fun append(chain: List<Entry>, time: Long, kind: Kind, ref: Long, payload: String): Entry {
        val prev = chain.lastOrNull()?.hash ?: GENESIS
        return Entry((chain.lastOrNull()?.seq ?: 0) + 1, time, kind, ref, payload, prev, hash(prev, kind, payload))
    }

    /** 기존 표에서 체인을 처음 만든다 (DB 마이그레이션·시연 데이터). 시각 순서대로 쌓는다 */
    fun build(trips: List<Trip>, expenses: List<Expense>): List<Entry> {
        val items = trips.map { Triple(it.endTime, Kind.TRIP, it.id to canonical(it)) } +
            expenses.map { Triple(it.time, Kind.EXPENSE, it.id to canonical(it)) }
        val out = ArrayList<Entry>()
        for ((time, kind, rp) in items.sortedWith(compareBy({ it.first }, { it.second.ordinal }, { it.third.first }))) {
            out += append(out, time, kind, rp.first, rp.second)
        }
        return out
    }

    data class Report(
        val ok: Boolean,
        val count: Int,
        /** 처음 어긋난 줄 번호 (1부터). 정상이면 null */
        val firstBadSeq: Long? = null,
        /** 어긋난 까닭 (화면용) */
        val reason: String = "",
        /** 어긋난 줄이 가리키는 기록 */
        val badKind: Kind? = null,
        val badRef: Long? = null,
        val lastHash: String = GENESIS,
        val anchorsChecked: Int = 0,
    )

    /**
     * 체인을 처음부터 다시 계산하고, 앵커와 현재 표를 대조한다.
     * 어느 줄의 내용이 바뀌면 그 줄부터 뒤의 hash 가 모두 달라지므로, 처음 어긋난 줄 번호를 알려 준다.
     */
    fun verify(chain: List<Entry>, anchors: List<DailyAnchor>, trips: List<Trip>, expenses: List<Expense>): Report {
        var prev = GENESIS
        val recomputed = HashMap<Long, String>()
        for ((i, e) in chain.withIndex()) {
            val expectSeq = i + 1L
            val h = hash(prev, e.kind, e.payload)
            if (e.seq != expectSeq) return bad(chain, e, "줄 번호가 빠지거나 바뀜", anchors = anchors.size)
            if (e.prevHash != prev || e.hash != h) return bad(chain, e, "기록 내용이 체인과 다름", anchors = anchors.size)
            recomputed[e.seq] = h
            prev = h
        }
        for (a in anchors) {
            val h = recomputed[a.seq]
            if (h == null || h != a.hash || !receiptOk(a)) {
                val e = chain.firstOrNull { it.seq == a.seq } ?: chain.lastOrNull()
                return Report(false, chain.size, a.seq, "${a.day} 앵커와 체인이 다름", e?.kind, e?.ref, prev, anchors.size)
            }
        }
        // 화면 표의 값이 체인의 마지막 값과 같은지 (표만 몰래 고친 경우)
        val latest = latest(chain)
        for (t in trips) {
            val e = latest[TRIP_KEY to t.id]
            if (e == null || !matches(e.payload, t)) return bad(chain, e, "운행 표가 체인과 다름", Kind.TRIP, t.id, anchors.size)
        }
        for (x in expenses) {
            val e = latest[EXPENSE_KEY to x.id]
            if (e == null || !matches(e.payload, x)) return bad(chain, e, "비용 표가 체인과 다름", Kind.EXPENSE, x.id, anchors.size)
        }
        return Report(true, chain.size, lastHash = prev, anchorsChecked = anchors.size)
    }

    /** 영수증이 있으면 그 서명까지 맞아야 한다. 응답 대기("")·이관([Rfc3161.BACKFILL]) 앵커는 hash 만 대조한다 */
    fun receiptOk(a: DailyAnchor): Boolean = when {
        a.receipt.isEmpty() || a.receipt == Rfc3161.BACKFILL -> true
        Rfc3161.isSigned(a.receipt) -> Rfc3161.check(a.receipt, a.hash, a.day) != null
        else -> SimulatedTsa.check(a.receipt, a.hash, a.day)
    }

    private fun bad(chain: List<Entry>, e: Entry?, reason: String, kind: Kind? = e?.kind, ref: Long? = e?.ref, anchors: Int = 0) =
        Report(false, chain.size, e?.seq ?: (chain.size + 1L), reason, kind, ref, chain.lastOrNull()?.hash ?: GENESIS, anchors)

    private const val TRIP_KEY = "trip"
    private const val EXPENSE_KEY = "expense"

    /** 기록마다 체인의 마지막 줄 (삭제된 비용은 빠진다) */
    fun latest(chain: List<Entry>): Map<Pair<String, Long>, Entry> {
        val out = HashMap<Pair<String, Long>, Entry>()
        for (e in chain) {
            val key = (if (e.kind.isTrip) TRIP_KEY else EXPENSE_KEY) to e.ref
            if (e.kind == Kind.EXPENSE_DEL) out.remove(key) else out[key] = e
        }
        return out
    }

    // ---- 일일 앵커 ----

    /**
     * 하루치 체인의 끝 hash. 외부로는 [day] 와 [hash] 만 보낸다 (위치·주소·금액은 보내지 않는다).
     * [receipt] 는 타임스탬프 기관의 영수증 ([Rfc3161] 참고). [stampedAt] 은 서명을 받으면 기관 시각으로 바뀐다.
     */
    data class DailyAnchor(
        val day: String,
        val seq: Long,
        val hash: String,
        val count: Int,
        val stampedAt: Long,
        val receipt: String,
    )

    /** [day] (yyyy-MM-dd, KST) 가 끝날 때까지의 마지막 줄로 앵커를 만든다. 그날 기록이 없으면 null */
    fun anchorFor(chain: List<Entry>, day: String, stampedAt: Long, tz: TimeZone = TaxReport.KST, receipt: String = ""): DailyAnchor? =
        anchorFor(chain, chain.map { dayOf(it.time, tz) }, day, stampedAt, receipt)

    /** [days] 는 체인 줄마다의 날짜 (여러 날을 한꺼번에 마감할 때 날짜 계산을 한 번만 한다) */
    fun anchorFor(chain: List<Entry>, days: List<String>, day: String, stampedAt: Long, receipt: String = ""): DailyAnchor? {
        val todays = chain.indices.filter { days[it] == day }.map { chain[it] }
        if (todays.isEmpty()) return null
        val last = chain[chain.indices.last { days[it] <= day }]
        return DailyAnchor(day, last.seq, last.hash, todays.size, stampedAt, receipt)
    }

    fun dayOf(time: Long, tz: TimeZone = TaxReport.KST): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = time }
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 신고 서류용 검증 코드: 최종 hash 앞 16자리를 4자리씩 */
    fun verifyCode(hash: String): String = hash.take(16).uppercase(Locale.US).chunked(4).joinToString("-")

    /**
     * 검증 파일 (JSON). 체인 전체와 앵커·영수증을 담아, 앱 밖(검증 페이지·openssl)에서 다시 계산할 수 있게 한다.
     * 체인 줄의 내용(payload)에는 장소 이름·금액이 들어 있으므로 사용자가 직접 내보낼 때만 만든다.
     */
    fun exportJson(chain: List<Entry>, anchors: List<DailyAnchor>): String {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        val sb = StringBuilder()
        sb.append("{\"format\":\"popolog-ledger-1\",\"genesis\":").append(q(GENESIS))
        sb.append(",\"hashRule\":").append(q("sha256(prev + \"\\n\" + kind + \"\\n\" + payload)"))
        sb.append(",\"entries\":[")
        chain.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"seq\":").append(e.seq).append(",\"time\":").append(e.time).append(",\"kind\":").append(q(e.kind.name))
                .append(",\"ref\":").append(e.ref).append(",\"payload\":").append(q(e.payload))
                .append(",\"prev\":").append(q(e.prevHash)).append(",\"hash\":").append(q(e.hash)).append('}')
        }
        sb.append("],\"anchors\":[")
        anchors.forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            sb.append("{\"day\":").append(q(a.day)).append(",\"seq\":").append(a.seq).append(",\"hash\":").append(q(a.hash))
                .append(",\"count\":").append(a.count).append(",\"stampedAt\":").append(a.stampedAt)
                .append(",\"receipt\":").append(q(a.receipt)).append('}')
        }
        return sb.append("]}").toString()
    }
}

/**
 * 이전 버전 데모의 타임스탬프 시뮬레이션. 이제 앵커는 [Rfc3161] 로 실제 기관 서명을 받고, 이 객체는 예전에 만든 영수증을 읽을 때만 쓴다.
 *
 * 실제 서비스에서는 RFC 3161 TSA 나 공개 블록체인 앵커링에 날짜와 hash 만 보내고, 기관 서명이 든 영수증을 받는다.
 * 여기서는 앱 안에서 같은 모양의 가짜 영수증을 만든다. 서명 자리는 고정 키의 SHA-256 이라 위조 방지 효력이 없다.
 */
object SimulatedTsa {
    const val NAME = "시뮬레이션 타임스탬프 (데모)"
    private const val DEMO_KEY = "popolog-demo-tsa-not-a-real-signature"

    /** 기관에 보내는 값: 날짜와 hash 뿐 */
    fun request(hash: String, day: String): String = "$day|$hash"

    fun stamp(hash: String, day: String, time: Long): String {
        val req = request(hash, day)
        val sig = Integrity.sha256("$DEMO_KEY|$req|$time").take(32)
        return "SIMULATED-TSA|$req|$time|$sig"
    }

    fun check(receipt: String, hash: String, day: String): Boolean {
        val p = receipt.split('|')
        if (p.size != 5 || p[0] != "SIMULATED-TSA" || p[1] != day || p[2] != hash) return false
        val time = p[3].toLongOrNull() ?: return false
        return stamp(hash, day, time) == receipt
    }
}
