package ai.chagok.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 복구에 PIN 또는 복구 코드가 필요하다 */
class SecretRequired : Exception("백업을 만들 때 쓴 관리자 PIN 또는 복구 코드를 입력하세요")
class WrongSecret : Exception("PIN 또는 복구 코드가 맞지 않습니다")

/**
 * 공용 폴더 백업 암호화. 백업마다 난수 데이터 키(DK)로 내용을 AES-GCM 암호화하고,
 * DK를 관리자 PIN 유도 키와 복구 코드 유도 키로 각각 감싸 파일 머리말에 함께 둔다.
 * 자동 백업에는 PIN 입력이 없으므로 유도 키(KEK)는 Keystore로 감싸 이 설치에만 보관한다.
 * 파일 머리말에는 만든 시각과 내보내기 파일 목록만 평문이다(매각 삭제가 키 없이 목록을 읽기 위함).
 */
object BackupCrypto {
    const val FORMAT = "popolog-backup-enc"
    private val aad = "popolog-backup-v1".toByteArray()
    private val random = SecureRandom()

    fun isEnvelope(bytes: ByteArray): Boolean = runCatching { header(bytes).optString("format") == FORMAT }.getOrDefault(false)

    fun header(bytes: ByteArray): JSONObject = JSONObject(String(bytes, Charsets.UTF_8))

    fun seal(snapshot: ByteArray, keys: BackupKeys): ByteArray {
        val entries = keys.entries()
        check(entries.isNotEmpty()) { "관리자 PIN으로 인증해야 암호화 백업을 만들 수 있습니다" }
        val dk = ByteArray(32).also(random::nextBytes)
        val plain = JSONObject(String(snapshot, Charsets.UTF_8))
        val payload = JSONObject(plain.getString("payload"))
        val wraps = JSONArray()
        for (e in entries) {
            val (iv, ct) = encrypt(e.kek, dk)
            wraps.put(JSONObject().put("type", e.type).put("salt", b64(e.salt)).put("iv", b64(iv)).put("key", b64(ct)))
        }
        val (iv, ct) = encrypt(dk, snapshot)
        dk.fill(0)
        return JSONObject().put("format", FORMAT).put("version", 1)
            .put("createdAt", plain.getLong("createdAt")).put("demo", plain.getBoolean("demo"))
            .put("kdf", "PBKDF2WithHmacSHA256/${BackupKeys.ITERATIONS}")
            .put("files", payload.getJSONArray("files")).put("folders", payload.getJSONArray("folders"))
            .put("wraps", wraps).put("iv", b64(iv)).put("data", b64(ct))
            .toString().toByteArray(Charsets.UTF_8)
    }

    /** 이 설치의 보관 키로 열 수 있으면 비밀 없이, 아니면 [secret]으로 유도해 연다 */
    fun open(envelope: ByteArray, keys: BackupKeys, secret: String?): ByteArray {
        val h = header(envelope)
        require(h.getString("format") == FORMAT && h.getInt("version") == 1) { "지원하지 않는 백업 버전" }
        val wraps = h.getJSONArray("wraps")
        var dk: ByteArray? = null
        for (i in 0 until wraps.length()) {
            val w = wraps.getJSONObject(i)
            val salt = Base64.decode(w.getString("salt"), Base64.NO_WRAP)
            val kek = keys.entries().firstOrNull { it.type == w.getString("type") && it.salt.contentEquals(salt) }?.kek
                ?: secret?.let { BackupKeys.derive(if (w.getString("type") == "recovery") BackupKeys.normalizeRecovery(it) else it, salt) }
                ?: continue
            dk = runCatching { decrypt(kek, b64d(w, "iv"), b64d(w, "key")) }.getOrNull() ?: continue
            break
        }
        if (dk == null) throw if (secret == null) SecretRequired() else WrongSecret()
        return try { decrypt(dk, b64d(h, "iv"), b64d(h, "data")) } finally { dk.fill(0) }
    }

    /** 현재 설치의 PIN 키로 만든 파일인가 (이 파일만 이전 세대로 남긴다) */
    fun sameKeys(h: JSONObject, keys: BackupKeys): Boolean {
        val current = keys.entries()
        if (current.isEmpty()) return false
        val wraps = h.getJSONArray("wraps")
        return current.all { e -> (0 until wraps.length()).any { i ->
            val w = wraps.getJSONObject(i)
            w.getString("type") == e.type && Base64.decode(w.getString("salt"), Base64.NO_WRAP).contentEquals(e.salt)
        } }
    }

    private fun encrypt(key: ByteArray, data: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); updateAAD(aad) }
        return c.iv to c.doFinal(data)
    }

    private fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad) }
        return c.doFinal(data)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(o: JSONObject, k: String) = Base64.decode(o.getString(k), Base64.NO_WRAP)
}

/**
 * 이 설치의 백업 유도 키. PIN·복구 코드를 바꾸면 새로 만들고, 이전 키로 만든 세대는 새 세대 기록 뒤에 정리된다.
 * 업무 DB·백업에는 들어가지 않는다.
 */
class BackupKeys(context: Context) {
    class Entry(val type: String, val salt: ByteArray, val kek: ByteArray)

    private val prefs = context.getSharedPreferences("backup_keys", Context.MODE_PRIVATE)

    @Synchronized fun entries(): List<Entry> = listOf("pin", "recovery").mapNotNull { type ->
        val salt = prefs.getString("${type}_salt", null) ?: return@mapNotNull null
        val wrapped = prefs.getString("${type}_kek", null) ?: return@mapNotNull null
        runCatching { Entry(type, Base64.decode(salt, Base64.NO_WRAP), unbox(wrapped)) }.getOrNull()
    }

    val hasPin: Boolean get() = prefs.contains("pin_kek")

    /** PIN을 새로 정했을 때. 복구 코드도 새로 발급되었으면 함께 넘긴다 */
    @Synchronized fun install(pin: String, recovery: String?) {
        val e = prefs.edit().clear()
        put(e, "pin", pin)
        if (recovery != null) put(e, "recovery", normalizeRecovery(recovery))
        check(e.commit()) { "백업 키 저장 실패" }
    }

    /** 업데이트 전에 설정된 관리자: PIN 인증 때 PIN 키만 만든다. 복구 코드는 PIN을 다시 정할 때 새로 발급된다 */
    @Synchronized fun ensurePin(pin: String) {
        if (hasPin) return
        val e = prefs.edit()
        put(e, "pin", pin)
        check(e.commit()) { "백업 키 저장 실패" }
    }

    @Synchronized fun clear() {
        prefs.edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun put(e: android.content.SharedPreferences.Editor, type: String, secret: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val kek = derive(secret, salt)
        e.putString("${type}_salt", Base64.encodeToString(salt, Base64.NO_WRAP)).putString("${type}_kek", box(kek))
        kek.fill(0)
    }

    private fun box(bytes: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        return Base64.encodeToString(c.iv + c.doFinal(bytes), Base64.NO_WRAP)
    }

    private fun unbox(s: String): ByteArray {
        val raw = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, raw, 0, 12)) }
        return c.doFinal(raw, 12, raw.size - 12)
    }

    private fun wrapKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        const val ITERATIONS = 210_000
        private const val ALIAS = "popolog.backup-kek.v1"
        fun normalizeRecovery(s: String) = s.replace("-", "").replace(" ", "").uppercase(java.util.Locale.ROOT)
        fun derive(secret: String, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        }
    }
}
