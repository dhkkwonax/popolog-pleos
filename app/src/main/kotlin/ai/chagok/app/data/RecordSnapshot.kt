package ai.chagok.app.data

import ai.chagok.app.BuildConfig
import ai.chagok.app.core.Integrity
import android.content.ContentValues
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Versioned logical snapshot. Never copy an open SQLite file or regenerate its ledger. */
object RecordSnapshot {
    const val MAX_BYTES = 64 * 1024 * 1024
    private val tables = listOf("place", "trip", "expense", "setting", "ledger", "anchor", "sync_item", "submission")
    private val localSettings = setOf("privacy_ack")

    fun create(db: ChagokDb, files: JSONArray, folders: JSONArray): ByteArray {
        val sql = db.writableDatabase
        val data = JSONObject()
        val sequences = JSONObject()
        sql.beginTransaction()
        try {
            require(Integrity.verify(db.ledger(), db.anchors(), db.trips(), db.expenses()).ok) { "현재 기록 무결성 검증 실패" }
            for (table in tables) {
                val rows = JSONArray()
                sql.rawQuery("SELECT * FROM $table", null).use { c ->
                    while (c.moveToNext()) {
                        if (table == "setting" && c.getString(c.getColumnIndexOrThrow("k")) in localSettings) continue
                        val row = JSONObject()
                        c.columnNames.forEachIndexed { i, name ->
                            row.put(name, when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                else -> error("지원하지 않는 데이터 형식")
                            })
                        }
                        rows.put(row)
                    }
                }
                data.put(table, rows)
            }
            sql.rawQuery("SELECT name, seq FROM sqlite_sequence", null).use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(0) in listOf("place", "trip", "expense", "sync_item", "submission")) sequences.put(cursor.getString(0), cursor.getLong(1))
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        val payload = JSONObject().put("tables", data).put("files", files).put("folders", folders).put("sequences", sequences).toString()
        return JSONObject().put("format", "popolog-records").put("version", 1).put("schema", sql.version)
            .put("demo", BuildConfig.DEMO).put("createdAt", System.currentTimeMillis())
            .put("payload", payload).put("sha256", digest(payload.toByteArray(Charsets.UTF_8)))
            .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "백업 용량 한도 초과" } }
    }

    fun decode(bytes: ByteArray, checkMode: Boolean = true): JSONObject {
        require(bytes.size <= MAX_BYTES) { "백업 용량 한도 초과" }
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        require(envelope.getString("format") == "popolog-records" && envelope.getInt("version") == 1 && envelope.getInt("schema") in 4..5) { "지원하지 않는 백업 버전" }
        require(!checkMode || envelope.getBoolean("demo") == BuildConfig.DEMO) { "데모·실제 모드가 다른 백업입니다" }
        val payload = envelope.getString("payload")
        require(digest(payload.toByteArray(Charsets.UTF_8)) == envelope.getString("sha256")) { "백업 체크섬 불일치" }
        return JSONObject(payload)
    }

    fun restore(db: ChagokDb, bytes: ByteArray): JSONArray {
        val payload = decode(bytes)
        val data = payload.getJSONObject("tables")
        val files = payload.getJSONArray("files")
        // 스키마 4 백업에는 제출 표가 없다. 빈 표로 복구한다
        if (!data.has("submission")) data.put("submission", JSONArray())
        require(data.keys().asSequence().toSet() == tables.toSet()) { "백업 표 목록 불일치" }
        val sql = db.writableDatabase
        val ack = db.setting("privacy_ack")
        sql.beginTransaction()
        try {
            for (table in tables) {
                val columns = linkedMapOf<String, String>()
                sql.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    while (c.moveToNext()) columns[c.getString(1)] = c.getString(2)
                }
                val rows = data.getJSONArray(table)
                require(rows.length() <= 250_000) { "백업 행 수 한도 초과" }
                sql.delete(table, null, null)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    require(row.keys().asSequence().toSet() == columns.keys) { "백업 열 목록 불일치" }
                    val values = ContentValues()
                    for ((name, type) in columns) {
                        val v = row.get(name)
                        if (v == JSONObject.NULL) {
                            require((table == "expense" && name in setOf("odo", "quantity")) || (table == "sync_item" && name == "odo")) { "필수 데이터가 비어 있습니다: $table.$name" }
                            values.putNull(name); continue
                        }
                        when (type) {
                            "INTEGER" -> { require(v is Int || v is Long); values.put(name, (v as Number).toLong()) }
                            "REAL" -> { require(v is Number && v.toDouble().isFinite()); values.put(name, (v as Number).toDouble()) }
                            "TEXT" -> { require(v is String); values.put(name, v as String) }
                            else -> error("지원하지 않는 백업 열 형식")
                        }
                    }
                    require(table != "setting" || row.getString("k") !in localSettings) { "설치별 설정이 포함된 백업" }
                    sql.insertOrThrow(table, null, values)
                }
            }
            val places = db.places()
            val trips = db.trips()
            val expenses = db.expenses()
            db.drivers(); db.syncItems()
            require(places.all { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }) { "장소 좌표 범위 오류" }
            require(trips.all { it.startLat in -90.0..90.0 && it.endLat in -90.0..90.0 && it.startLng in -180.0..180.0 && it.endLng in -180.0..180.0 }) { "운행 좌표 범위 오류" }
            val chain = db.ledger()
            val sequences = payload.getJSONObject("sequences")
            require(sequences.keys().asSequence().all { it in listOf("place", "trip", "expense", "sync_item", "submission") }) { "ID 순번 표 불일치" }
            for (table in listOf("place", "trip", "expense", "sync_item", "submission")) {
                val high = sequences.opt(table) ?: 0L
                require((high is Int || high is Long) && (high as Number).toLong() >= 0) { "ID 순번 형식 오류" }
                val currentMax = sql.rawQuery("SELECT COALESCE(MAX(id),0) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
                val historicalMax = when (table) {
                    "trip" -> chain.filter { it.kind.isTrip }.maxOfOrNull { it.ref } ?: 0
                    "expense" -> chain.filter { !it.kind.isTrip }.maxOfOrNull { it.ref } ?: 0
                    else -> 0
                }
                val next = maxOf((high as Number).toLong(), currentMax, historicalMax)
                sql.delete("sqlite_sequence", "name=?", arrayOf(table))
                sql.insertOrThrow("sqlite_sequence", null, ContentValues().apply { put("name", table); put("seq", next) })
            }
            val latest = Integrity.latest(chain).values
            require(latest.filter { it.kind.isTrip }.map { it.ref }.toSet() == trips.map { it.id }.toSet() &&
                latest.filter { !it.kind.isTrip }.map { it.ref }.toSet() == expenses.map { it.id }.toSet()) { "원장 참조 불일치" }
            val report = Integrity.verify(chain, db.anchors(), trips, expenses)
            require(report.ok) { "기록 무결성 검증 실패" }
            if (ack.isNotEmpty()) db.setSetting("privacy_ack", ack)
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        return files
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
