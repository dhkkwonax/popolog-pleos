package ai.chagok.app.core

import ai.chagok.app.core.Integrity.Kind
import ai.chagok.app.model.ClassifiedBy
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrityTest {
    // 2026-10-27 09:00 KST 부터 한 시간 간격
    private val t0 = 1_792_972_800_000L

    private fun trip(id: Long, i: Int, purpose: TripPurpose = TripPurpose.COMMUTE) = Trip(
        id = id, startTime = t0 + i * 3_600_000L, endTime = t0 + i * 3_600_000L + 1_800_000L,
        startOdoKm = 8000.0 + i * 20, endOdoKm = 8000.0 + i * 20 + 18.4,
        startLat = 37.0, startLng = 127.0, endLat = 37.1, endLng = 127.1,
        purpose = purpose, classifiedBy = ClassifiedBy.PLACE, fromName = "집", toName = "회사", driver = "김도윤",
    )

    private fun fuel(id: Long, i: Int, amount: Long = 84_500) = Expense(
        id = id, time = t0 + i * 3_600_000L + 600_000L, category = ExpenseCategory.FUEL, amountWon = amount, odoKm = 8000.0 + i * 20,
    )

    private val trips = (1..5).map { trip(it.toLong(), it) }
    private val expenses = listOf(fuel(1, 2), fuel(2, 4))
    private val chain = Integrity.build(trips, expenses)

    @Test
    fun hashLinksToPreviousAndStartsAtGenesis() {
        assertEquals(7, chain.size)
        assertEquals(Integrity.GENESIS, chain.first().prevHash)
        for (i in 1 until chain.size) assertEquals(chain[i - 1].hash, chain[i].prevHash)
        val e = chain[3]
        assertEquals(Integrity.sha256(e.prevHash + "\n" + e.kind.name + "\n" + e.payload), e.hash)
        assertEquals(64, e.hash.length)
        // 시각 순서대로 쌓인다
        assertEquals(chain.map { it.time }.sorted(), chain.map { it.time })
    }

    @Test
    fun canonicalChangesWhenAnyRecordedFieldChanges() {
        val t = trips[0]
        val base = Integrity.canonical(t)
        assertNotEquals(base, Integrity.canonical(t.copy(endOdoKm = t.endOdoKm + 0.1)))
        assertNotEquals(base, Integrity.canonical(t.copy(purpose = TripPurpose.BUSINESS)))
        assertNotEquals(base, Integrity.canonical(t.copy(driver = "박하나")))
        assertNotEquals(base, Integrity.canonical(t.copy(startTime = t.startTime + 1)))
        // 체인에 넣지 않는 값(좌표)은 바뀌어도 같다
        assertEquals(base, Integrity.canonical(t.copy(endLat = 0.0)))
    }

    @Test
    fun intactChainVerifies() {
        val r = Integrity.verify(chain, emptyList(), trips, expenses)
        assertTrue(r.ok, r.reason)
        assertEquals(7, r.count)
        assertEquals(chain.last().hash, r.lastHash)
    }

    @Test
    fun correctionIsAppendedAndTableShowsLatest() {
        val fixed = trips[1].copy(purpose = TripPurpose.BUSINESS, classifiedBy = ClassifiedBy.MANUAL)
        val c2 = chain + Integrity.append(chain, t0 + 99, Kind.TRIP_FIX, fixed.id, Integrity.canonical(fixed))
        // 원래 줄은 그대로 남는다
        assertEquals(chain, c2.dropLast(1))
        val table = trips.map { if (it.id == fixed.id) fixed else it }
        assertTrue(Integrity.verify(c2, emptyList(), table, expenses).ok)
        // 정정 기록 없이 표만 바꾸면 드러난다
        val r = Integrity.verify(chain, emptyList(), table, expenses)
        assertFalse(r.ok)
        assertEquals(Kind.TRIP, r.badKind)
        assertEquals(fixed.id, r.badRef)
    }

    @Test
    fun deletedExpenseLeavesTrailAndDropsFromTable() {
        val c2 = chain + Integrity.append(chain, t0 + 99, Kind.EXPENSE_DEL, 2, "deleted")
        assertTrue(Integrity.verify(c2, emptyList(), trips, expenses.filter { it.id != 2L }).ok)
        assertEquals(chain.size + 1, c2.size)
    }

    @Test
    fun tamperingMiddleEntryFailsFromThatPoint() {
        val i = 3
        val forged = trips.first { it.id == chain[i].ref }.let { it.copy(endOdoKm = it.endOdoKm + 30) }
        val tampered = chain.toMutableList().apply { this[i] = this[i].copy(payload = Integrity.canonical(forged)) }
        val table = trips.map { if (it.id == forged.id) forged else it }
        val r = Integrity.verify(tampered, emptyList(), table, expenses)
        assertFalse(r.ok)
        assertEquals(chain[i].seq, r.firstBadSeq)
        // 그 줄의 hash 까지 다시 써도, 다음 줄이 앞 hash 를 들고 있어 다음 줄에서 걸린다
        val rehashed = tampered.toMutableList().apply {
            this[i] = this[i].copy(hash = Integrity.hash(this[i].prevHash, this[i].kind, this[i].payload))
        }
        assertEquals(chain[i + 1].seq, Integrity.verify(rehashed, emptyList(), table, expenses).firstBadSeq)
    }

    @Test
    fun rebuiltChainIsCaughtByAnchor() {
        val day = Integrity.dayOf(chain.last().time)
        val anchor = Integrity.anchorFor(chain, day, t0 + 86_400_000L)!!
        assertTrue(Integrity.verify(chain, listOf(anchor), trips, expenses).ok)
        // 기록 하나를 고치고 체인을 처음부터 새로 만들면 체인 자체는 맞지만 앵커와 다르다
        val forged = trips.map { if (it.id == 2L) it.copy(endOdoKm = it.endOdoKm + 30) else it }
        val rebuilt = Integrity.build(forged, expenses)
        assertTrue(Integrity.verify(rebuilt, emptyList(), forged, expenses).ok)
        val r = Integrity.verify(rebuilt, listOf(anchor), forged, expenses)
        assertFalse(r.ok)
        assertTrue(r.reason.contains("앵커"))
    }

    @Test
    fun anchorCoversDayAndSendsOnlyHash() {
        val day = Integrity.dayOf(chain.first().time)
        val a = Integrity.anchorFor(chain, day, t0 + 86_400_000L)!!
        val todays = chain.filter { Integrity.dayOf(it.time) == day }
        assertEquals(todays.size, a.count)
        assertEquals(todays.last().hash, a.hash)
        assertEquals(todays.last().seq, a.seq)
        // 기관에 보내는 값에는 날짜와 hash 만 있다. 처음 만든 앵커는 기관 응답 전이라 영수증이 비어 있다
        assertEquals("$day|${a.hash}", SimulatedTsa.request(a.hash, day))
        assertEquals("", a.receipt)
        assertTrue(Integrity.receiptOk(a))
        assertNull(Integrity.anchorFor(chain, "2000-01-01", t0))
    }

    @Test
    fun legacySimulatedReceiptStillVerifies() {
        val day = Integrity.dayOf(chain.first().time)
        val a = Integrity.anchorFor(chain, day, t0)!!
        val legacy = a.copy(receipt = SimulatedTsa.stamp(a.hash, day, t0))
        assertTrue(Integrity.verify(chain, listOf(legacy), trips, expenses).ok)
        assertFalse(Integrity.verify(chain, listOf(legacy.copy(receipt = legacy.receipt.replace(day, "2000-01-01"))), trips, expenses).ok)
    }

    /** freetsa.org 가 실제로 서명한 토큰 (test/resources). 서명·지문을 오프라인으로 확인한다 */
    @Test
    fun rfc3161TokenSignatureAndImprint() {
        val (day, hash, receipt) = javaClass.getResource("/freetsa-anchor.txt")!!.readText().trim().lines()
        val st = Rfc3161.check(receipt, hash, day)!!
        assertTrue(st.tsa.contains("freetsa", ignoreCase = true))
        assertEquals("2026-10-28", Integrity.dayOf(st.time))
        // 다른 hash·날짜에는 맞지 않는다
        assertNull(Rfc3161.check(receipt, Integrity.GENESIS, day))
        assertNull(Rfc3161.check(receipt, hash, "2026-10-26"))
        // 토큰을 한 글자라도 바꾸면 서명이 깨진다
        val b64 = receipt.substringAfterLast('|')
        val i = b64.length / 2
        val flipped = receipt.substringBeforeLast('|') + "|" + b64.substring(0, i) + (if (b64[i] == 'A') 'B' else 'A') + b64.substring(i + 1)
        assertNull(Rfc3161.check(flipped, hash, day))
        assertEquals("freetsa.org", Rfc3161.host(receipt))
    }

    @Test
    fun memoIsChainedButOldV1LinesStillMatch() {
        val t = trips[0].copy(memo = "거래처 미팅")
        assertNotEquals(Integrity.canonical(t), Integrity.canonical(t.copy(memo = "개인 용무")))
        // v1 줄(메모 없음)은 메모가 있어도 v1 규칙으로 대조한다
        val v1 = Integrity.canonical(t, 1)
        assertTrue(v1.startsWith("trip|"))
        assertTrue(Integrity.matches(v1, t))
        assertFalse(Integrity.matches(Integrity.canonical(t.copy(memo = "x")), t))
        val e = expenses[0].copy(memo = "현금")
        assertTrue(Integrity.matches(Integrity.canonical(e, 1), e))
        assertFalse(Integrity.matches(Integrity.canonical(e.copy(memo = "카드")), e))
    }

    @Test
    fun exportJsonHasEveryEntryAndAnchor() {
        val a = Integrity.anchorFor(chain, Integrity.dayOf(chain.last().time), t0)!!
        val json = Integrity.exportJson(chain, listOf(a))
        assertEquals(chain.size, Regex("\\\"seq\\\":").findAll(json.substringBefore("\"anchors\"")).count())
        assertTrue(json.contains(chain.last().hash))
        assertTrue(json.contains("\"day\":\"${a.day}\""))
    }

    @Test
    fun verifyCodeGroupsFirstSixteenHexDigits() {
        assertEquals("ABCD-EF01-2345-6789", Integrity.verifyCode("abcdef0123456789ffff"))
    }
}
