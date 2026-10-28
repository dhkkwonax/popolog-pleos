package ai.chagok.app.ui

import ai.chagok.app.core.Integrity
import ai.chagok.app.core.Rfc3161
import ai.chagok.app.core.TaxReport
import ai.chagok.app.core.TaxSummary
import ai.chagok.app.model.VehicleInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 신고용 「업무용승용차 운행기록부」(별지 제63호) PDF. 본문 행은 CSV 와 같은 [TaxReport.logbookRows] 를 쓴다.
 * 첫 장 오른쪽 위에 검증 상자(검증 코드·기간·건수·최종 앵커)와 QR 을 둔다.
 * 인쇄용이라 화면 테마와 상관없이 흰 바탕에 검정 글자다.
 */
object LogbookPdf {
    /** 검증 페이지 주소. 같은 페이지가 앱에도 들어 있다 (assets/verify.html, PDF 미리보기의 '검증 페이지') */
    const val VERIFY_URL = "https://verify.popolog.app/v1"

    data class Input(
        val year: Int,
        val rows: List<List<String>>,
        val summary: TaxSummary,
        val vehicle: VehicleInfo,
        val company: String,
        val bizNumber: String,
        /** 내보낼 때의 체인 끝 hash 와 줄 수 */
        val finalHash: String,
        val chainCount: Int,
        val lastAnchor: Integrity.DailyAnchor?,
    )

    /** QR 에 담는 검증용 데이터 (최종 hash·기간·건수·앵커). 주소는 데모용 자리표시 */
    fun qrText(i: Input): String =
        "$VERIFY_URL?h=${i.finalHash}&p=${i.year}0101-${i.year}1231&n=${i.rows.size}&c=${i.chainCount}" +
            (i.lastAnchor?.let { "&a=${it.day}" } ?: "") + if (ai.chagok.app.BuildConfig.DEMO) "&demo=1" else ""

    private const val W = 842 // A4 가로 (pt)
    private const val H = 595
    private const val M = 36
    private const val ROWS_PER_PAGE = 22

    fun build(i: Input): ByteArray {
        val doc = PdfDocument()
        val pages = i.rows.chunked(ROWS_PER_PAGE).ifEmpty { listOf(emptyList()) }
        for ((n, rows) in pages.withIndex()) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n + 1).create())
            val c = page.canvas
            var y = if (n == 0) header(c, i) else M + 10f
            y = table(c, rows, y)
            if (n == pages.lastIndex) totals(c, i, y + 14)
            footer(c, i, n + 1, pages.size)
            doc.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun header(c: Canvas, i: Input): Float {
        c.drawText("업무용승용차 운행기록부", M.toFloat(), M + 18f, paint(20f, bold = true))
        val p = paint(10f)
        val lines = listOf(
            "사업연도  ${i.year}-01-01 ~ ${i.year}-12-31",
            "법인명  ${i.company}      사업자등록번호  ${i.bizNumber}",
            "차종  ${i.vehicle.modelName}      자동차등록번호  ${i.vehicle.plateNumber}",
        )
        lines.forEachIndexed { k, s -> c.drawText(s, M.toFloat(), M + 44f + k * 16, p) }

        // 검증 상자: 오른쪽 위
        val qr = 96f
        val boxW = 330f
        val left = W - M - boxW
        val top = M.toFloat() - 6
        val box = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(120, 120, 120); strokeWidth = 0.8f }
        c.drawRect(left, top, W - M.toFloat(), top + qr + 16, box)
        drawQr(c, qrText(i), left + 8, top + 8, qr)
        val tx = left + qr + 18
        val anchor = i.lastAnchor?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREAN).apply { timeZone = TaxReport.KST }.format(it.stampedAt)
        } ?: "없음"
        c.drawText("기록 무결성 검증", tx, top + 20, paint(10f, bold = true))
        c.drawText(Integrity.verifyCode(i.finalHash), tx, top + 42, paint(15f, bold = true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        val small = paint(8.5f)
        c.drawText("기간 ${i.year}-01-01 ~ ${i.year}-12-31 · 운행 ${i.rows.size}건 · 체인 ${i.chainCount}줄", tx, top + 60, small)
        c.drawText("최종 앵커 $anchor", tx, top + 74, small)
        val tsa = i.lastAnchor?.receipt?.takeIf { Rfc3161.isSigned(it) }?.let { "RFC 3161 타임스탬프 · ${Rfc3161.host(it)}" } ?: "기관 타임스탬프 대기"
        c.drawText("($tsa)", tx, top + 86, small)
        c.drawText("검증 파일로 체인을 다시 계산할 수 있습니다", tx, top + 98, paint(8.5f, color = Color.rgb(90, 90, 90)))
        return M + 44f + lines.size * 16 + 18
    }

    private fun drawQr(c: Canvas, text: String, x: Float, y: Float, size: Float) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0))
        val cell = size / m.width
        val p = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (yy in 0 until m.height) for (xx in 0 until m.width) {
            if (m.get(xx, yy)) c.drawRect(x + xx * cell, y + yy * cell, x + (xx + 1) * cell, y + (yy + 1) * cell, p)
        }
    }

    /** 열 너비 (pt). 합계가 본문 폭 (W - 2M) */
    private val COL_W = floatArrayOf(78f, 56f, 50f, 70f, 70f, 56f, 66f, 66f, 258f)
    private val HEAD = listOf("사용일자(요일)", "부서", "성명", "주행 전\n계기판(km)", "주행 후\n계기판(km)", "주행거리\n(km)", "출·퇴근용\n(km)", "일반 업무용\n(km)", "비고")

    private fun table(c: Canvas, rows: List<List<String>>, top: Float): Float {
        val line = Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = 0.6f }
        val headP = paint(8f, bold = true)
        val cellP = paint(8f)
        val headH = 26f
        val rowH = 17f
        var x = M.toFloat()
        c.drawRect(M.toFloat(), top, W - M.toFloat(), top + headH, Paint().apply { color = Color.rgb(236, 238, 241) })
        for ((k, h) in HEAD.withIndex()) {
            h.split('\n').forEachIndexed { j, s -> c.drawText(s, x + 4, top + 11 + j * 10, headP) }
            x += COL_W[k]
        }
        var y = top + headH
        c.drawLine(M.toFloat(), y, W - M.toFloat(), y, line)
        for (r in rows) {
            x = M.toFloat()
            for ((k, v) in r.withIndex()) {
                val numeric = k in 3..7
                val s = ellipsize(v, cellP, COL_W[k] - 8)
                val tx = if (numeric) x + COL_W[k] - 4 - cellP.measureText(s) else x + 4
                c.drawText(s, tx, y + 12, cellP)
                x += COL_W[k]
            }
            y += rowH
            c.drawLine(M.toFloat(), y, W - M.toFloat(), y, line)
        }
        return y
    }

    private fun ellipsize(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    private fun totals(c: Canvas, i: Input, top: Float) {
        val s = i.summary
        val p = paint(9.5f, bold = true)
        fun km(v: Double) = String.format(Locale.US, "%,.1f km", v)
        c.drawText(
            "사업연도 총주행거리 ${km(s.totalKm)}    업무용 사용거리 ${km(s.businessUseKm)}    업무사용비율 ${String.format(Locale.US, "%.1f", s.businessRatio * 100)}%",
            M.toFloat(), top + 10, p,
        )
        c.drawText("기록 출처: 차량 계기판 누적거리(Pleos Vehicle SDK Odometer) 자동 기록 · 기록마다 SHA-256 해시체인", M.toFloat(), top + 26, paint(8.5f))
    }

    private fun footer(c: Canvas, i: Input, page: Int, pages: Int) {
        val p = paint(8f, color = Color.rgb(90, 90, 90))
        c.drawText("Popolog · 검증 코드 ${Integrity.verifyCode(i.finalHash)}", M.toFloat(), H - 18f, p)
        val s = "$page / $pages"
        c.drawText(s, W - M - p.measureText(s), H - 18f, p)
    }
}
