package ai.chagok.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 앱 DB 암호화 키. 최초 실행에 난수 키를 만들어 Android Keystore 키로 감싼 채 보관한다.
 * Keystore 키는 기기 밖으로 나오지 않으므로 앱 데이터 폴더만 복사해서는 DB를 열 수 없다.
 * 앱 제거·데이터 초기화 때 키와 DB가 함께 사라진다.
 */
object DbCrypto {
    private const val DB_NAME = "chagok.db"
    private const val KEY_ALIAS = "popolog.db.v1"
    private const val PREFS = "db_key"
    private val lock = Any()

    init { System.loadLibrary("sqlcipher") }

    /** SQLCipher 원시 키 형식(x'..') 바이트. 기존 평문 DB가 있으면 열기 전에 암호화 DB로 옮긴다 */
    fun prepare(context: Context): ByteArray = synchronized(lock) {
        val key = loadOrCreateKey(context)
        val pass = "x'${key.joinToString("") { "%02x".format(it) }}'".toByteArray(Charsets.US_ASCII)
        key.fill(0)
        migratePlaintext(context, pass)
        pass
    }

    /** 전체 삭제 후 이전 키로 새 DB가 만들어지지 않게 한다 */
    fun forget(context: Context) = synchronized(lock) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
        Unit
    }

    private fun loadOrCreateKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("wrapped", null)
        if (stored != null) {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, wrapKey(create = false), GCMParameterSpec(128, raw, 0, 12))
            }
            return c.doFinal(raw, 12, raw.size - 12)
        }
        // 키가 없는데 암호화 DB만 남은 상태는 복구할 수 없다. 새 키로 덮지 않고 오류로 알린다
        check(!isEncrypted(context.getDatabasePath(DB_NAME))) { "데이터베이스 암호 키를 사용할 수 없습니다" }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey(create = true)) }
        val wrapped = c.iv + c.doFinal(key)
        check(prefs.edit().putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()) { "암호 키 저장 실패" }
        return key
    }

    private fun wrapKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "기기 보안 키를 사용할 수 없습니다" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun isEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val head = ByteArray(16)
        val n = file.inputStream().use { it.read(head) }
        return n < 16 || String(head, Charsets.ISO_8859_1) != "SQLite format 3\u0000"
    }

    /** 평문 DB → 암호화 DB. 확인이 끝난 뒤에만 평문 파일을 지우고, 실패하면 평문을 남겨 다음 실행에서 다시 시도한다 */
    private fun migratePlaintext(context: Context, pass: ByteArray) {
        val plain = context.getDatabasePath(DB_NAME)
        if (!plain.exists() || plain.length() == 0L || isEncrypted(plain)) return
        val tmp = File(plain.parentFile, "$DB_NAME.enc.tmp")
        listOf(tmp, File(tmp.path + "-journal"), File(tmp.path + "-wal"), File(tmp.path + "-shm")).forEach { it.delete() }
        val keyLiteral = String(pass, Charsets.US_ASCII)
        val src = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(plain.path, ByteArray(0), null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE or net.zetetic.database.sqlcipher.SQLiteDatabase.CREATE_IF_NECESSARY, null, null)
        try {
            val version = src.version
            src.execSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS encrypted KEY \"$keyLiteral\"")
            src.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { it.moveToFirst() }
            src.execSQL("PRAGMA encrypted.user_version = $version")
            src.execSQL("DETACH DATABASE encrypted")
        } finally { src.close() }
        // 새 파일이 키로 열리고 행 수가 같은지 확인한다
        val check = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(tmp.path, pass, null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY, null, null)
        val plainDb = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(plain.path, ByteArray(0), null, net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY, null, null)
        try {
            val tables = plainDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null)
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            for (t in tables) {
                val a = plainDb.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { it.moveToFirst(); it.getLong(0) }
                val b = check.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { it.moveToFirst(); it.getLong(0) }
                require(a == b) { "암호화 이전 확인 실패: $t" }
            }
        } finally { check.close(); plainDb.close() }
        // 평문 파일(저널 포함)을 제거하고 암호화 파일로 교체
        File(plain.path + "-journal").delete(); File(plain.path + "-wal").delete(); File(plain.path + "-shm").delete()
        require(plain.delete()) { "평문 데이터베이스 삭제 실패" }
        require(tmp.renameTo(plain)) { "암호화 데이터베이스 교체 실패" }
    }
}
