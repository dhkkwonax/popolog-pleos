package ai.chagok.app.core

import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cms.SignerId
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.bouncycastle.tsp.TimeStampResponse
import org.bouncycastle.tsp.TimeStampToken
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 일일 앵커의 외부 타임스탬프 (RFC 3161). 기관에는 "날짜|hash" 의 SHA-256 만 보낸다 (위치·주소·금액은 보내지 않는다).
 *
 * 영수증(receipt) 문자열:
 * - `RFC3161|<기관 주소>|<토큰 base64>`: 기관이 서명한 타임스탬프 토큰
 * - `""`: 아직 기관 응답 전 (오프라인이면 다음에 다시 요청)
 * - [BACKFILL]: 기존 기록을 처음 체인으로 옮길 때 만든 지난 날 앵커. 서명은 마지막 날 앵커 하나만 받는다
 *   (체인은 앞 줄 hash 를 모두 품으므로 마지막 앵커 서명이 그 앞 기록까지 묶는다)
 * - `SIMULATED-TSA|…`: 이전 버전 데모가 만든 시뮬레이션 영수증 ([SimulatedTsa])
 */
object Rfc3161 {
    const val PREFIX = "RFC3161"
    const val BACKFILL = "BACKFILL"

    /** 차례로 시도한다. 둘 다 https 라 평문 통신 예외 설정이 필요 없다 */
    val SERVERS = listOf("https://freetsa.org/tsr", "https://timestamp.sectigo.com")

    private val bc = BouncyCastleProvider()

    /** 기관에 보내는 지문 */
    fun imprint(hash: String, day: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(SimulatedTsa.request(hash, day).toByteArray(Charsets.UTF_8))

    /**
     * 기관에 요청해 영수증을 받는다. 네트워크를 쓰므로 메인 스레드에서 부르지 않는다. 모두 실패하면 null.
     * [post] 는 테스트에서 바꿔 끼운다.
     */
    fun stamp(hash: String, day: String, post: (String, ByteArray) -> ByteArray = ::httpPost): String? {
        for (url in SERVERS) {
            val token = runCatching {
                val nonce = BigInteger(63, SecureRandom())
                val req = TimeStampRequestGenerator().apply { setCertReq(true) }
                    .generate(TSPAlgorithms.SHA256, imprint(hash, day), nonce)
                val resp = TimeStampResponse(post(url, req.encoded))
                resp.validate(req)
                resp.timeStampToken
            }.getOrNull() ?: continue
            val receipt = "$PREFIX|$url|" + Base64.getEncoder().encodeToString(token.encoded)
            if (check(receipt, hash, day) != null) return receipt
        }
        return null
    }

    data class Stamp(val time: Long, val tsa: String)

    /** 토큰 서명(토큰에 든 기관 인증서로)과 지문이 이 날짜·hash 와 맞으면 기관 시각을 돌려준다 */
    fun check(receipt: String, hash: String, day: String): Stamp? = runCatching {
        val p = receipt.split('|')
        if (p.size != 3 || p[0] != PREFIX) return null
        val token = TimeStampToken(ContentInfo.getInstance(Base64.getDecoder().decode(p[2])))
        val info = token.timeStampInfo
        if (info.messageImprintAlgOID != NISTObjectIdentifiers.id_sha256) return null
        if (!info.messageImprintDigest.contentEquals(imprint(hash, day))) return null
        val sid: SignerId = token.sid
        val cert = token.certificates.getMatches(null).firstOrNull { sid.match(it) } ?: return null
        token.validate(JcaSimpleSignerInfoVerifierBuilder().setProvider(bc).build(cert))
        val cn = cert.subject.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.toString() ?: URL(p[1]).host
        Stamp(info.genTime.time, cn)
    }.getOrNull()

    fun isSigned(receipt: String) = receipt.startsWith("$PREFIX|")

    fun host(receipt: String): String = receipt.split('|').getOrNull(1)?.let { runCatching { URL(it).host }.getOrNull() }.orEmpty()

    private fun httpPost(url: String, body: ByteArray): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/timestamp-query")
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) error("TSA HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
