package ai.chagok.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Installation credentials never enter the record DB or its snapshots. */
class AdminAuth(context: Context) {
    private val prefs = context.getSharedPreferences("local_admin", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    @Volatile var unlocked = false
        private set
    @Volatile var generation = 0L
        private set
    private var verifiedAt = 0L
    private var verifiedGeneration: Long? = null
    val configured: Boolean get() = prefs.contains("pin")
    val waitSeconds: Long get() = ((prefs.getLong("until", 0) - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)

    @Synchronized fun lock() { unlocked = false; verifiedAt = 0; verifiedGeneration = null; generation++ }

    @Synchronized fun authenticate(pin: String, expectedGeneration: Long): Boolean {
        check(waitSeconds == 0L) { "${waitSeconds}초 후 다시 입력해 주세요" }
        if (expectedGeneration != generation) return false
        unlocked = false
        verifiedAt = 0
        verifiedGeneration = null
        if (!verify(pin, "pin")) { failed(); return false }
        check(prefs.edit().putInt("failures", 0).putLong("until", 0).commit()) { "인증 상태 저장 실패" }
        verifiedGeneration = generation
        return true
    }

    @Synchronized fun openSession(expectedGeneration: Long) {
        check(expectedGeneration == generation && verifiedGeneration == generation && configured) { "다시 인증해 주세요" }
        verifiedGeneration = null
        unlocked = true
        verifiedAt = android.os.SystemClock.elapsedRealtime()
    }

    @Synchronized fun setup(pin: String, expectedGeneration: Long): String {
        check(!configured) { "관리자가 이미 설정되어 있습니다" }
        check(expectedGeneration == generation) { "다시 설정해 주세요" }
        return store(pin)
    }

    @Synchronized fun replace(pin: String, expectedGeneration: Long): String {
        check(expectedGeneration == generation && recentlyVerified()) { "현재 PIN을 다시 확인해 주세요" }
        return store(pin)
    }

    @Synchronized fun recover(code: String, pin: String, expectedGeneration: Long): String {
        check(waitSeconds == 0L) { "${waitSeconds}초 후 다시 입력해 주세요" }
        check(expectedGeneration == generation) { "다시 복구해 주세요" }
        if (!verify(code.replace("-", "").replace(" ", "").uppercase(java.util.Locale.ROOT), "recovery")) {
            failed()
            error("복구 코드가 일치하지 않습니다")
        }
        return store(pin)
    }

    @Synchronized fun recentlyVerified(): Boolean = unlocked &&
        android.os.SystemClock.elapsedRealtime() - verifiedAt in 0..60_000

    @Synchronized fun clearAfterSale() {
        lock()
        check(prefs.edit().clear().commit()) { "관리자 정보 삭제 실패" }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    private fun store(pin: String): String {
        require(pin.matches(Regex("[0-9]{6}"))) { "PIN은 숫자 6자리입니다" }
        val recovery = ByteArray(16).also(random::nextBytes).joinToString("") { "%02X".format(it.toInt() and 255) }
        val salt = ByteArray(32).also(random::nextBytes)
        val pinHash = digest(pin, salt)
        val recoveryHash = digest(recovery, salt)
        check(prefs.edit().clear().putString("salt", encode(salt))
            .putString("pin", encode(pinHash)).putString("recovery", encode(recoveryHash)).commit()) { "관리자 정보 저장 실패" }
        unlocked = false
        verifiedAt = 0
        verifiedGeneration = null
        return recovery.chunked(4).joinToString("-")
    }

    private fun failed() {
        val n = (prefs.getInt("failures", 0) + 1).coerceAtMost(20)
        val delay = if (n < 5) 0L else (30L shl (n - 5).coerceAtMost(5))
        check(prefs.edit().putInt("failures", n).putLong("until", System.currentTimeMillis() + delay * 1000).commit()) { "입력 대기 상태 저장 실패" }
    }

    private fun verify(input: String, field: String): Boolean {
        if (!configured || input.length > 64) return false
        val salt = prefs.getString("salt", null) ?: error("관리자 정보가 손상되었습니다")
        val expected = prefs.getString(field, null) ?: error("관리자 정보가 손상되었습니다")
        return MessageDigest.isEqual(Base64.decode(expected, Base64.NO_WRAP), digest(input, Base64.decode(salt, Base64.NO_WRAP)))
    }

    private fun digest(input: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(input.toCharArray(), salt, 210_000, 256)
        val derived = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try { Mac.getInstance("HmacSHA256").apply { init(key()) }.doFinal(derived) }
        finally { derived.fill(0) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(!configured) { "기기 인증 키를 사용할 수 없습니다" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).build())
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    companion object { private const val KEY_ALIAS = "popolog.local-admin.v1" }
}
