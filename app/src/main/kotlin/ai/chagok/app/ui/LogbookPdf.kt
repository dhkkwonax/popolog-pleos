package ai.chagok.app.ui

import ai.chagok.app.core.Integrity
import ai.chagok.app.core.TaxSummary
import ai.chagok.app.model.VehicleInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.util.Locale

/** 법인세법용 「업무용승용차 운행기록부」 인쇄 서식. 검증 정보는 별도 검증파일/페이지에서 제공한다. */
object LogbookPdf {
    const val VERIFY_URL = "https://verify.popolog.app/v1"

    data class Input(
        val year: Int,
        val rows: List<List<String>>,
        val summary: TaxSummary,
        val vehicle: VehicleInfo,
        val company: String,
        val bizNumber: String,
        val finalHash: String,
        val chainCount: Int,
        val lastAnchor: Integrity.DailyAnchor?,
    )

    fun qrText(i: Input): String =
        "$VERIFY_URL?h=${i.finalHash}&p=${i.year}0101-${i.year}1231&n=${i.rows.size}&c=${i.chainCount}" +
            (i.lastAnchor?.let { "&a=${it.day}" } ?: "") + if (ai.chagok.app.BuildConfig.DEMO) "&demo=1" else ""

    private const val W = 842 // A4 landscape, points
    private const val H = 595
    private const val M = 30f
    private const val ROWS_PER_PAGE = 11
    private const val ROW_H = 20f
    private val widths = floatArrayOf(74f, 70f, 60f, 87f, 87f, 70f, 82f, 90f, 162f)

    fun build(i: Input): ByteArray {
        val doc = PdfDocument()
        val pages = i.rows.chunked(ROWS_PER_PAGE).ifEmpty { listOf(emptyList()) }
        for ((pageIndex, rows) in pages.withIndex()) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageIndex + 1).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)
            drawHeader(c, i)
            val tableBottom = drawTable(c, rows, 180f)
            if (pageIndex == pages.lastIndex) drawTotals(c, i, tableBottom + 8f)
            if (pages.size > 1) centered(c, "${pageIndex + 1} / ${pages.size}", W / 2f, H - 14f, 8f, color = GRAY)
            doc.finishPage(page)
        }
        return ByteArrayOutputStream().use { out ->
            doc.writeTo(out)
            doc.close()
            out.toByteArray()
        }
    }

    private val GRAY = 0xff777777.toInt()
    private val GRID = 0xff777777.toInt()
    private val LIGHT = 0xffe6e6e6.toInt()

    private fun textPaint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun drawHeader(c: Canvas, i: Input) {
        val top = 24f
        val h = 47f
        val yearW = 190f
        val titleW = 315f
        val rightW = W - 2 * M - yearW - titleW
        val xYear = M
        val xTitle = xYear + yearW
        val xRight = xTitle + titleW
        rect(c, xYear, top, yearW, h)
        rect(c, xTitle, top, titleW, h)
        rect(c, xRight, top, rightW, h)
        line(c, xRight, top + h / 2, xRight + rightW, top + h / 2)
        left(c, "사업연도", xYear + 7, top + 18, 9f)
        centered(c, "${i.year}. 1. 1.  ~  ${i.year}. 12. 31.", xYear + yearW / 2, top + 36, 9f)
        centered(c, "업무용승용차 운행기록부", xTitle + titleW / 2, top + 29, 17f, bold = true)
        left(c, "법인명", xRight + 7, top + 16, 8f)
        left(c, i.company, xRight + 65, top + 16, 9f)
        left(c, "사업자등록번호", xRight + 7, top + 40, 8f)
        left(c, i.bizNumber, xRight + 90, top + 40, 9f)

        left(c, "1. 기본정보", M, 91f, 10f, bold = true)
        val infoY = 101f
        val infoH = 26f
        val infoW = (W - 2 * M) / 2
        rect(c, M, infoY, infoW, infoH)
        rect(c, M + infoW, infoY, infoW, infoH)
        left(c, "① 차종", M + 8, infoY + 17, 8.5f)
        left(c, i.vehicle.modelName, M + 95, infoY + 15, 9f)
        left(c, "② 자동차등록번호", M + infoW + 8, infoY + 17, 8.5f)
        left(c, i.vehicle.plateNumber, M + infoW + 110, infoY + 15, 9f)
        left(c, "2. 업무용 사용비율 계산", M, 164f, 10f, bold = true)
    }

    private fun drawTable(c: Canvas, rows: List<List<String>>, top: Float): Float {
        val xs = FloatArray(widths.size + 1)
        xs[0] = M
        for (k in widths.indices) xs[k + 1] = xs[k] + widths[k]
        val groupH = 24f
        val subH = 30f
        val headBottom = top + groupH + subH

        // Group headers and subheaders mirror the official merged-cell layout.
        val whole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIGHT }
        c.drawRect(M, top, W - M, headBottom, whole)
        cell(c, xs[0], top, widths[0], groupH + subH, "③ 사용일자\n(요일)", 7.3f)
        cell(c, xs[1], top, widths[1] + widths[2], groupH, "④ 사용자", 7.3f)
        cell(c, xs[1], top + groupH, widths[1], subH, "부서", 7.5f)
        cell(c, xs[2], top + groupH, widths[2], subH, "성명", 7.5f)
        cell(c, xs[3], top, widths[3], groupH + subH, "⑤ 주행 전\n계기판의 거리(km)", 7.1f)
        cell(c, xs[4], top, widths[4], groupH + subH, "⑥ 주행 후\n계기판의 거리(km)", 7.1f)
        cell(c, xs[5], top, widths[5], groupH + subH, "⑦ 주행거리\n(km)", 7.3f)
        cell(c, xs[6], top, widths[6] + widths[7], groupH, "업무용 사용거리(km)", 7.3f)
        cell(c, xs[6], top + groupH, widths[6], subH, "⑧ 출·퇴근용\n(km)", 7.1f)
        cell(c, xs[7], top + groupH, widths[7], subH, "⑨ 일반 업무용\n(km)", 7.1f)
        cell(c, xs[8], top, widths[8], groupH + subH, "⑩ 비고", 7.5f)

        var y = headBottom
        for (rowIndex in 0 until ROWS_PER_PAGE) {
            val row = rows.getOrNull(rowIndex).orEmpty()
            c.drawRect(M, y, W - M, y + ROW_H, Paint().apply { color = Color.WHITE })
            for (col in widths.indices) {
                val value = row.getOrNull(col).orEmpty()
                cell(c, xs[col], y, widths[col], ROW_H, value, 7.5f, alignRight = col in 3..7)
            }
            y += ROW_H
        }
        // Outer and cell rules are drawn after fills so the form remains a clear print grid.
        val topOfTable = top
        val bottom = y
        xs.forEachIndexed { index, x ->
            val startsBelowGroup = index == 2 || index == 7
            line(c, x, if (startsBelowGroup) top + groupH else topOfTable, x, bottom)
        }
        line(c, M, top, W - M, top)
        line(c, xs[1], top + groupH, xs[3], top + groupH)
        line(c, xs[6], top + groupH, xs[8], top + groupH)
        line(c, M, headBottom, W - M, headBottom)
        for (n in 1..ROWS_PER_PAGE) line(c, M, headBottom + n * ROW_H, W - M, headBottom + n * ROW_H)
        return bottom
    }

    private fun drawTotals(c: Canvas, i: Input, top: Float) {
        val xs = FloatArray(widths.size + 1)
        xs[0] = M
        for (k in widths.indices) xs[k + 1] = xs[k] + widths[k]
        val h = 19f
        val leftW = widths.take(3).sum()
        c.drawRect(M, top, xs[3], top + h * 2, Paint().apply { color = LIGHT })
        cell(c, M, top, leftW, h * 2, "합계", 8.5f, bold = true)
        cell(c, xs[3], top, widths[3] + widths[4] + widths[5], h, "⑪ 사업연도 총 주행거리(km)", 8f)
        cell(c, xs[3], top + h, widths[3] + widths[4] + widths[5], h, km(i.summary.totalKm), 8.5f, bold = true)
        cell(c, xs[6], top, widths[6] + widths[7], h, "⑫ 사업연도 업무용 사용거리(km)", 8f)
        cell(c, xs[6], top + h, widths[6] + widths[7], h, km(i.summary.businessUseKm), 8.5f, bold = true)
        cell(c, xs[8], top, widths[8], h, "⑬ 업무사용비율(⑫/⑪)", 8f)
        cell(c, xs[8], top + h, widths[8], h, String.format(Locale.US, "%.1f%%", i.summary.businessRatio * 100), 8.5f, bold = true)
        line(c, M, top, W - M, top)
        line(c, M, top + h, W - M, top + h)
        line(c, M, top + h * 2, W - M, top + h * 2)
        for (x in listOf(M, xs[3], xs[6], xs[8], W - M)) line(c, x, top, x, top + h * 2)
    }

    private fun km(value: Double) = String.format(Locale.US, "%.1f", value)

    private fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        c.drawRect(x, y, x + w, y + h, Paint().apply { color = GRID; style = Paint.Style.STROKE; strokeWidth = 0.6f })
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, Paint().apply { color = GRID; strokeWidth = 0.6f })
    }

    private fun cell(c: Canvas, x: Float, y: Float, w: Float, h: Float, value: String, size: Float,
                     bold: Boolean = false, alignRight: Boolean = false) {
        rect(c, x, y, w, h)
        val p = textPaint(size, bold)
        val lines = value.split('\n')
        val lineHeight = size + 2f
        val firstBaseline = y + (h - lines.size * lineHeight) / 2f + size
        lines.forEachIndexed { n, raw ->
            val s = fit(raw, p, w - 8f)
            val tx = when {
                alignRight -> x + w - 4f - p.measureText(s)
                else -> x + (w - p.measureText(s)) / 2f
            }
            c.drawText(s, tx, firstBaseline + n * lineHeight, p)
        }
    }

    private fun fit(value: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 0 && p.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.take(end) + "…"
    }

    private fun left(c: Canvas, value: String, x: Float, y: Float, size: Float, bold: Boolean = false) {
        c.drawText(fit(value, textPaint(size, bold), 800f), x, y, textPaint(size, bold))
    }

    private fun centered(c: Canvas, value: String, x: Float, y: Float, size: Float, bold: Boolean = false, color: Int = Color.BLACK) {
        val p = textPaint(size, bold, color)
        c.drawText(fit(value, p, 800f), x - p.measureText(fit(value, p, 800f)) / 2, y, p)
    }
}
