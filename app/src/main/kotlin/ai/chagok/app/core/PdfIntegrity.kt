package ai.chagok.app.core

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.SignerId
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.bouncycastle.tsp.TimeStampResponse
import org.bouncycastle.tsp.TimeStampToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CertificateFactory
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Calendar

/** PDF 바이트 자체를 보호하는 문서 타임스탬프. 원장 일일 앵커와 별개다. */
class PdfIntegrity(context: Context) {
    enum class Status { VALID, UNSIGNED, INVALID, UNTRUSTED }
    data class Report(val status: Status, val time: Long? = null)

    private val bc = BouncyCastleProvider()
    private val root = context.assets.open("tsa/freetsa-root.pem").use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    /** 호출자는 백그라운드에서 실행해야 한다. 성공한 PDF만 반환하며 실패 시 원본을 증명된 것으로 돌려주지 않는다. */
    fun stamp(pdf: ByteArray): ByteArray {
        require(pdf.size <= MAX_BYTES) { "PDF가 너무 큽니다" }
        val output = ByteArrayOutputStream()
        PDDocument.load(pdf).use { document ->
            require(document.signatureDictionaries.isEmpty()) { "이미 서명이 있는 PDF입니다" }
            val signature = PDSignature().apply {
                setType(COSName.getPDFName("DocTimeStamp"))
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(COSName.getPDFName("ETSI.RFC3161"))
                signDate = Calendar.getInstance()
            }
            SignatureOptions().use { options ->
                options.preferredSignatureSize = 32768
                document.addSignature(signature, options)
                val signing = document.saveIncrementalForExternalSigning(output)
                val digest = signing.content.use(::digest)
                val token = request(digest)
                checkToken(token, digest)
                signing.setSignature(token)
            }
        }
        val result = output.toByteArray()
        check(verify(result).status == Status.VALID) { "생성된 PDF의 증명을 확인하지 못했습니다" }
        return result
    }

    /** 서명된 범위 밖에 추가된 주석·페이지·후속 저장도 미변경으로 통과시키지 않는다. */
    fun verify(pdf: ByteArray): Report {
        if (pdf.size > MAX_BYTES) return Report(Status.INVALID)
        return try {
            PDDocument.load(pdf).use { document ->
                val signatures = document.signatureDictionaries
                if (signatures.isEmpty()) return Report(Status.UNSIGNED)
                val signature = signatures.singleOrNull() ?: return Report(Status.UNTRUSTED)
                if (signature.cosObject.getNameAsString(COSName.TYPE) != "DocTimeStamp" ||
                    signature.subFilter != "ETSI.RFC3161") return Report(Status.UNTRUSTED)
                val range = signature.byteRange
                require(range.size == 4 && range[0] == 0 && range[1] > 0 &&
                    range[2] > range[1] && range[3] > 0 &&
                    range[2].toLong() + range[3] == pdf.size.toLong())
                val contents = signature.contents
                // 제외 구간은 오직 <Contents의 16진수>여야 한다. 다른 PDF 명령을 숨기지 못하게 한다.
                require(range[2].toLong() - range[1] == contents.size.toLong() * 2 + 2)
                require(pdf[range[1]] == '<'.code.toByte() && pdf[range[2] - 1] == '>'.code.toByte())
                for (i in contents.indices) {
                    val hi = Character.digit(pdf[range[1] + 1 + i * 2].toInt().toChar(), 16)
                    val lo = Character.digit(pdf[range[1] + 2 + i * 2].toInt().toChar(), 16)
                    require(hi >= 0 && lo >= 0 && ((hi shl 4) or lo).toByte() == contents[i])
                }
                val token = ASN1InputStream(contents).use { it.readObject().encoded }
                require(token.size <= contents.size && token.indices.all { token[it] == contents[it] })
                require((token.size until contents.size).all { contents[it] == 0.toByte() })
                val hash = MessageDigest.getInstance("SHA-256").apply {
                    update(pdf, 0, range[1])
                    update(pdf, range[2], range[3])
                }.digest()
                Report(Status.VALID, checkToken(token, hash))
            }
        } catch (_: UntrustedCertificate) {
            Report(Status.UNTRUSTED)
        } catch (_: Exception) {
            Report(Status.INVALID)
        }
    }

    private class UntrustedCertificate(cause: Exception) : Exception(cause)

    private fun checkToken(encoded: ByteArray, digest: ByteArray): Long {
        val token = TimeStampToken(ContentInfo.getInstance(encoded))
        val info = token.timeStampInfo
        require(info.messageImprintAlgOID == NISTObjectIdentifiers.id_sha256)
        require(info.messageImprintDigest.contentEquals(digest))
        val sid: SignerId = token.sid
        val holder = token.certificates.getMatches(null).firstOrNull { sid.match(it) }
            ?: throw IllegalArgumentException("서명 인증서가 없습니다")
        token.validate(JcaSimpleSignerInfoVerifierBuilder().setProvider(bc).build(holder))
        try {
            val converter = JcaX509CertificateConverter().setProvider(bc)
            val signer = converter.getCertificate(holder)
            signer.checkValidity(info.genTime)
            root.checkValidity(info.genTime)
            val selector = X509CertSelector().apply { certificate = signer }
            val parameters = PKIXBuilderParameters(setOf(TrustAnchor(root, null)), selector).apply {
                date = info.genTime
                // shortcut: 폐기 증거 보관·조회는 별도 후속 작업. 화면에서 미확인임을 밝힌다.
                isRevocationEnabled = false
                addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(
                    token.certificates.getMatches(null).map(converter::getCertificate),
                )))
            }
            CertPathBuilder.getInstance("PKIX").build(parameters)
        } catch (e: Exception) {
            throw UntrustedCertificate(e)
        }
        return info.genTime.time
    }

    private fun request(digest: ByteArray): ByteArray {
        val nonce = BigInteger(128, SecureRandom())
        val request = TimeStampRequestGenerator().apply { setCertReq(true) }
            .generate(NISTObjectIdentifiers.id_sha256, digest, nonce)
        val connection = URL("https://freetsa.org/tsr").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/timestamp-query")
            connection.setRequestProperty("Accept", "application/timestamp-reply")
            connection.outputStream.use { it.write(request.encoded) }
            check(connection.responseCode == 200) { "타임스탬프 기관 응답을 받지 못했습니다" }
            val response = TimeStampResponse(connection.inputStream.use { readLimited(it, 256 * 1024) })
            response.validate(request)
            val token = response.timeStampToken ?: error("타임스탬프 발급이 거절됐습니다")
            require(kotlin.math.abs(token.timeStampInfo.genTime.time - System.currentTimeMillis()) <= 5 * 60_000) {
                "기기 시각과 기관 시각을 확인해 주세요"
            }
            return token.encoded
        } finally {
            connection.disconnect()
        }
    }

    private fun digest(input: InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            hash.update(buffer, 0, n)
        }
        return hash.digest()
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024

        fun readLimited(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size().toLong() + n <= limit) { "파일이 너무 큽니다" }
                output.write(buffer, 0, n)
            }
            return output.toByteArray()
        }
    }
}
