package ai.chagok.app.data

import ai.chagok.app.core.Integrity
import ai.chagok.app.core.Retention
import ai.chagok.app.model.ClassifiedBy
import ai.chagok.app.model.Driver
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.ExpenseSource
import ai.chagok.app.model.Place
import ai.chagok.app.model.PlaceKind
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/** SQLCipher로 전체 암호화한 저장소 (키는 [DbCrypto]가 Keystore로 보호) */
class ChagokDb(context: Context) : SQLiteOpenHelper(context, "chagok.db", DbCrypto.prepare(context), null, 5, 0, null, null, false) {

    val revision = java.util.concurrent.atomic.AtomicLong()

    /** 지운 행의 내용이 빈 페이지에 남지 않게 한다 (운행 원문 파기용) */
    override fun onOpen(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA secure_delete=ON", null).close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE place (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, kind TEXT, lat REAL, lng REAL, " +
                "radius INTEGER, address TEXT)",
        )
        db.execSQL(
            "CREATE TABLE trip (id INTEGER PRIMARY KEY AUTOINCREMENT, start_time INTEGER, end_time INTEGER, " +
                "start_odo REAL, end_odo REAL, start_lat REAL, start_lng REAL, end_lat REAL, end_lng REAL, " +
                "purpose TEXT, classified_by TEXT, from_name TEXT, to_name TEXT, memo TEXT, driver TEXT, " +
                "logbook_user TEXT NOT NULL DEFAULT '', logbook_department TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL(
            "CREATE TABLE expense (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER, category TEXT, amount INTEGER, " +
                "odo REAL, quantity REAL, memo TEXT, source TEXT, pending INTEGER, shop TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL("CREATE TABLE setting (k TEXT PRIMARY KEY, v TEXT)")
        createIntegrityTables(db)
        createSyncTable(db)
        createSubmissionTable(db)
    }

    /** v2: 위변조 검증용 해시체인(ledger)·일일 앵커(anchor). 체인은 AppController.start 가 기존 기록으로 처음 만든다 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createIntegrityTables(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE trip ADD COLUMN logbook_user TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE trip ADD COLUMN logbook_department TEXT NOT NULL DEFAULT ''")
        }
        // v4: 비용에 결제한 곳(shop), 외부 연동 내역 보관함(sync_item)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE expense ADD COLUMN shop TEXT NOT NULL DEFAULT ''")
            createSyncTable(db)
        }
        // v5: 관리자의 제출 완료 확인(보존 기산점·변경 불가 대상)
        if (oldVersion < 5) createSubmissionTable(db)
    }

    private fun createSubmissionTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE submission (id INTEGER PRIMARY KEY AUTOINCREMENT, confirmed_at INTEGER, year INTEGER, " +
                "trip_ids TEXT, expense_ids TEXT, pdf_name TEXT, pdf_sha TEXT, years INTEGER, statutory_end TEXT)",
        )
    }

    private fun createSyncTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sync_item (id INTEGER PRIMARY KEY AUTOINCREMENT, external_id TEXT UNIQUE, time INTEGER, " +
                "category TEXT, amount INTEGER, shop TEXT, odo REAL, memo TEXT, state TEXT)",
        )
    }

    private fun createIntegrityTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ledger (seq INTEGER PRIMARY KEY, time INTEGER, kind TEXT, ref INTEGER, payload TEXT, " +
                "prev_hash TEXT, hash TEXT)",
        )
        db.execSQL("CREATE TABLE anchor (day TEXT PRIMARY KEY, seq INTEGER, hash TEXT, count INTEGER, stamped_at INTEGER, receipt TEXT)")
    }

    // ---- place ----
    fun places(): List<Place> = readableDatabase.rawQuery("SELECT * FROM place ORDER BY kind, name", null).use { c ->
        c.map {
            Place(
                id = c.long("id"), name = c.str("name"), kind = PlaceKind.valueOf(c.str("kind")),
                lat = c.dbl("lat"), lng = c.dbl("lng"), radiusM = c.int("radius"), address = c.str("address"),
            )
        }
    }

    fun upsert(p: Place): Long = save("place", p.id, ContentValues().apply {
        put("name", p.name); put("kind", p.kind.name); put("lat", p.lat); put("lng", p.lng)
        put("radius", p.radiusM); put("address", p.address)
    })

    fun deletePlace(id: Long) {
        writableDatabase.delete("place", "id=?", arrayOf(id.toString()))
        revision.incrementAndGet()
    }

    // ---- trip ----
    fun trips(): List<Trip> = readableDatabase.rawQuery("SELECT * FROM trip ORDER BY start_time DESC", null).use { c ->
        c.map {
            Trip(
                id = c.long("id"), startTime = c.long("start_time"), endTime = c.long("end_time"),
                startOdoKm = c.dbl("start_odo"), endOdoKm = c.dbl("end_odo"),
                startLat = c.dbl("start_lat"), startLng = c.dbl("start_lng"),
                endLat = c.dbl("end_lat"), endLng = c.dbl("end_lng"),
                purpose = TripPurpose.valueOf(c.str("purpose")), classifiedBy = ClassifiedBy.valueOf(c.str("classified_by")),
                fromName = c.str("from_name"), toName = c.str("to_name"), memo = c.str("memo"), driver = c.str("driver"),
                logbookUser = c.str("logbook_user"), logbookDepartment = c.str("logbook_department"),
            )
        }
    }

    fun upsert(t: Trip): Long = save("trip", t.id, ContentValues().apply {
        put("start_time", t.startTime); put("end_time", t.endTime)
        put("start_odo", t.startOdoKm); put("end_odo", t.endOdoKm)
        put("start_lat", t.startLat); put("start_lng", t.startLng); put("end_lat", t.endLat); put("end_lng", t.endLng)
        put("purpose", t.purpose.name); put("classified_by", t.classifiedBy.name)
        put("from_name", t.fromName); put("to_name", t.toName); put("memo", t.memo); put("driver", t.driver)
        put("logbook_user", t.logbookUser); put("logbook_department", t.logbookDepartment)
    })

    /**
     * 미제출 운행 한 건의 원문을 파기한다: 운행 행 삭제, 원장의 그 운행 줄 payload 를 [Integrity.PURGED] 로 치환(hash 는 그대로),
     * [del] 줄 덧붙이기를 한 트랜잭션으로 한다. 이어서 WAL·빈 페이지에 남은 내용을 정리한다.
     * 저장소 암호화가 바뀌어도 이 호출의 의미(원문 제거 + 삭제 줄)는 유지한다.
     */
    fun purgeTrip(id: Long, del: Integrity.Entry) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.delete("trip", "id=?", arrayOf(id.toString()))
            w.execSQL(
                "UPDATE ledger SET payload=? WHERE ref=? AND kind IN (?, ?)",
                arrayOf(Integrity.PURGED + id, id, Integrity.Kind.TRIP.name, Integrity.Kind.TRIP_FIX.name),
            )
            appendLedger(del)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        runCatching { w.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
        runCatching { w.execSQL("VACUUM") }
        revision.incrementAndGet()
    }

    // ---- expense ----
    fun expenses(): List<Expense> = readableDatabase.rawQuery("SELECT * FROM expense ORDER BY time DESC", null).use { c ->
        c.map {
            Expense(
                id = c.long("id"), time = c.long("time"), category = ExpenseCategory.valueOf(c.str("category")),
                amountWon = c.long("amount"), odoKm = c.dblOrNull("odo"), quantity = c.dblOrNull("quantity"),
                memo = c.str("memo"), source = ExpenseSource.valueOf(c.str("source")), pendingAmount = c.int("pending") == 1,
                shop = c.str("shop"),
            )
        }
    }

    fun upsert(e: Expense): Long = save("expense", e.id, ContentValues().apply {
        put("time", e.time); put("category", e.category.name); put("amount", e.amountWon)
        if (e.odoKm == null) putNull("odo") else put("odo", e.odoKm)
        if (e.quantity == null) putNull("quantity") else put("quantity", e.quantity)
        put("memo", e.memo); put("source", e.source.name); put("pending", if (e.pendingAmount) 1 else 0)
        put("shop", e.shop)
    })

    fun deleteExpense(id: Long) {
        writableDatabase.delete("expense", "id=?", arrayOf(id.toString()))
        revision.incrementAndGet()
    }

    // ---- ledger (append-only) ----
    fun ledger(): List<Integrity.Entry> = readableDatabase.rawQuery("SELECT * FROM ledger ORDER BY seq", null).use { c ->
        c.map {
            Integrity.Entry(
                seq = c.long("seq"), time = c.long("time"), kind = Integrity.Kind.valueOf(c.str("kind")), ref = c.long("ref"),
                payload = c.str("payload"), prevHash = c.str("prev_hash"), hash = c.str("hash"),
            )
        }
    }

    /** 덧붙이기만 한다. 같은 seq 가 이미 있으면 실패한다 */
    fun appendLedger(e: Integrity.Entry) {
        writableDatabase.insertOrThrow("ledger", null, ContentValues().apply {
            put("seq", e.seq); put("time", e.time); put("kind", e.kind.name); put("ref", e.ref)
            put("payload", e.payload); put("prev_hash", e.prevHash); put("hash", e.hash)
        })
        revision.incrementAndGet()
    }

    /** 시연용 '기록 위조해 보기' 전용: 체인 한 줄의 내용을 몰래 바꾼다 */
    fun forgeLedgerPayload(seq: Long, payload: String) {
        writableDatabase.update("ledger", ContentValues().apply { put("payload", payload) }, "seq=?", arrayOf(seq.toString()))
        revision.incrementAndGet()
    }

    fun anchors(): List<Integrity.DailyAnchor> = readableDatabase.rawQuery("SELECT * FROM anchor ORDER BY day", null).use { c ->
        c.map {
            Integrity.DailyAnchor(
                day = c.str("day"), seq = c.long("seq"), hash = c.str("hash"), count = c.int("count"),
                stampedAt = c.long("stamped_at"), receipt = c.str("receipt"),
            )
        }
    }

    fun saveAnchor(a: Integrity.DailyAnchor) {
        writableDatabase.insertWithOnConflict("anchor", null, ContentValues().apply {
            put("day", a.day); put("seq", a.seq); put("hash", a.hash); put("count", a.count)
            put("stamped_at", a.stampedAt); put("receipt", a.receipt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        revision.incrementAndGet()
    }

    // ---- sync_item (외부 연동 내역 보관함) ----
    fun syncItems(state: String? = null): List<SyncItem> =
        readableDatabase.rawQuery(
            "SELECT * FROM sync_item" + (if (state != null) " WHERE state=?" else "") + " ORDER BY time DESC",
            if (state != null) arrayOf(state) else null,
        ).use { c ->
            c.map {
                SyncItem(
                    id = c.long("id"), externalId = c.str("external_id"), time = c.long("time"),
                    category = ExpenseCategory.valueOf(c.str("category")), amountWon = c.long("amount"),
                    shop = c.str("shop"), odoKm = c.dblOrNull("odo"), memo = c.str("memo"), state = c.str("state"),
                )
            }
        }

    /** 이미 받은 externalId 면 넣지 않는다 (중복 제거). 새로 넣었으면 true */
    fun addSyncItem(i: SyncItem): Boolean {
        val id = writableDatabase.insertWithOnConflict("sync_item", null, ContentValues().apply {
            put("external_id", i.externalId); put("time", i.time); put("category", i.category.name); put("amount", i.amountWon)
            put("shop", i.shop); if (i.odoKm == null) putNull("odo") else put("odo", i.odoKm); put("memo", i.memo); put("state", i.state)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (id != -1L) revision.incrementAndGet()
        return id != -1L
    }

    fun setSyncState(id: Long, state: String) {
        writableDatabase.update("sync_item", ContentValues().apply { put("state", state) }, "id=?", arrayOf(id.toString()))
        revision.incrementAndGet()
    }

    // ---- submission (제출 완료 확인) ----
    fun submissions(): List<Retention.Submission> =
        readableDatabase.rawQuery("SELECT * FROM submission ORDER BY confirmed_at DESC, id DESC", null).use { c ->
            c.map {
                Retention.Submission(
                    id = c.long("id"), confirmedAt = c.long("confirmed_at"), year = c.int("year"),
                    tripIds = ids(c.str("trip_ids")), expenseIds = ids(c.str("expense_ids")),
                    pdfName = c.str("pdf_name"), pdfSha256 = c.str("pdf_sha"),
                    retentionYears = c.int("years"), statutoryEnd = c.str("statutory_end"),
                )
            }
        }

    fun addSubmission(s: Retention.Submission): Long {
        val id = writableDatabase.insertOrThrow("submission", null, ContentValues().apply {
            put("confirmed_at", s.confirmedAt); put("year", s.year)
            put("trip_ids", s.tripIds.joinToString(",")); put("expense_ids", s.expenseIds.joinToString(","))
            put("pdf_name", s.pdfName); put("pdf_sha", s.pdfSha256)
            put("years", s.retentionYears); put("statutory_end", s.statutoryEnd)
        })
        revision.incrementAndGet()
        return id
    }

    fun setStatutoryEnd(id: Long, day: String) {
        writableDatabase.update("submission", ContentValues().apply { put("statutory_end", day) }, "id=?", arrayOf(id.toString()))
        revision.incrementAndGet()
    }

    /** 보존기간을 늘려도 이미 확인한 제출의 보존이 줄지 않게 각 제출의 연수는 올리기만 한다 */
    fun raiseRetention(years: Int) {
        writableDatabase.execSQL("UPDATE submission SET years=? WHERE years<?", arrayOf(years, years))
        revision.incrementAndGet()
    }

    private fun ids(s: String): List<Long> = s.split(',').mapNotNull { it.toLongOrNull() }

    // ---- setting ----
    fun setting(key: String, default: String = ""): String =
        readableDatabase.rawQuery("SELECT v FROM setting WHERE k=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else default
        }

    fun setSetting(key: String, value: String) {
        if (setting(key) == value) return
        writableDatabase.insertWithOnConflict(
            "setting", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE,
        )
        revision.incrementAndGet()
    }

    // ---- driver ----
    // 운전자는 몇 명뿐이라 표를 따로 두지 않고 설정에 한 줄씩 담는다: id \t 이름 \t 부서 \t 시트 위치 \t 동의 시각
    fun drivers(): List<Driver> = decodeDrivers(setting(KEY_DRIVERS))

    fun saveDrivers(list: List<Driver>) = setSetting(KEY_DRIVERS, encodeDrivers(list))

    fun clearAll() {
        writableDatabase.apply {
            delete("place", null, null); delete("trip", null, null); delete("expense", null, null); delete("setting", null, null)
            delete("ledger", null, null); delete("anchor", null, null); delete("sync_item", null, null); delete("submission", null, null)
        }
        revision.incrementAndGet()
    }

    private fun save(table: String, id: Long, v: ContentValues): Long {
        val db = writableDatabase
        val result = if (id == 0L) db.insertOrThrow(table, null, v) else {
            db.update(table, v, "id=?", arrayOf(id.toString())); id
        }
        revision.incrementAndGet()
        return result
    }
}

private const val KEY_DRIVERS = "drivers"

internal fun encodeDrivers(list: List<Driver>): String = list.joinToString("\n") { d ->
    listOf(d.id, d.name, d.department, d.seatPosition?.toString().orEmpty(), d.consentAt.toString())
        .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
}

internal fun decodeDrivers(s: String): List<Driver> = s.lines().filter { it.isNotBlank() }.mapNotNull { line ->
    val f = line.split('\t')
    if (f.size < 3) null else Driver(f[0], f[1], f[2], f.getOrNull(3)?.toIntOrNull(), f.getOrNull(4)?.toLongOrNull() ?: 0L)
}

private inline fun <T> Cursor.map(f: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(f(this))
    return out
}

private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
private fun Cursor.dbl(col: String): Double = getDouble(getColumnIndexOrThrow(col))
private fun Cursor.dblOrNull(col: String): Double? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }
