package ai.chagok.app.ui

import ai.chagok.app.MainActivity
import ai.chagok.app.core.PdfIntegrity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** PDF 증명은 일일 원장 증명과 분리한다. 결과는 요청 당시 관리자 세션에서만 보여 준다. */
class PdfControls(private val a: MainActivity) {
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private var selected: Uri? = null

    /** 이번 실행에서 마지막으로 저장한 PDF (이름, SHA-256). 제출 완료 확인 때 연결한다 */
    var lastSaved: Pair<String, String>? = null
        private set

    fun prepare(pdf: ByteArray, fileName: String, caption: String, ledger: String) {
        if (!a.requireAdmin()) return
        if (!a.controller.tsaEnabled) {
            preview(pdf, pdf, fileName, caption, ledger, false, "타임스탬프가 꺼져 있어 증명이 없습니다")
            return
        }
        run("PDF 증명을 발급하고 있습니다", { PdfIntegrity(a).stamp(pdf) }) { result ->
            val stamped = result.getOrNull()
            preview(stamped ?: pdf, pdf, fileName, caption, ledger, stamped != null,
                if (stamped != null) "파일 변경 없음 · FreeTSA 인증서 확인" else "증명 발급 실패 · 미증명 PDF입니다")
        }
    }

    private fun preview(pdf: ByteArray, original: ByteArray, fileName: String, caption: String,
                        ledger: String, proven: Boolean, status: String) {
        var dialog: Dialog? = null
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "PDF 증명 확인" to { inspect(pdf) },
            "원장 JSON 저장" to {
                a.saveFile(fileName.removeSuffix(".pdf") + "_원장.json", ledger.toByteArray(Charsets.UTF_8), "application/json")
            },
        )
        if (!proven) actions.add("PDF 증명 재시도" to {
            if (!a.requireAdmin()) return@to
            if (!a.controller.tsaEnabled) {
                a.toast("설정에서 타임스탬프를 켠 뒤 다시 내보내세요")
            } else {
                dialog?.dismiss()
                prepare(original, fileName, caption, ledger)
            }
        })
        dialog = a.showPdfPreview(
            if (proven) "운행기록부 PDF" else "운행기록부 PDF · 증명 없음",
            pdf, "$caption\n$status", actions,
        ) {
            val name = if (proven) fileName else fileName.removeSuffix(".pdf") + "_미증명.pdf"
            a.saveFile(name, pdf, "application/pdf")
            lastSaved = name to ai.chagok.app.data.RecordSnapshot.digest(pdf)
        }
    }

    fun selectFile() {
        if (!a.requireAdmin()) return
        selected?.let { uri -> inspectFile(uri); return }
        try {
            a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/pdf"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, REQUEST_PDF)
        } catch (_: ActivityNotFoundException) { a.toast("PDF 파일을 선택할 앱이 없습니다") }
    }

    fun onFileSelected(uri: Uri) {
        // 파일 선택기로 나가면 관리자 세션이 잠긴다. 재인증 뒤 검사 버튼으로 이어 간다.
        selected = uri
        a.toast("관리자 잠금 해제 후 세무 리포트의 ‘PDF 파일 검사’를 다시 누르세요")
    }

    private fun inspectFile(uri: Uri) {
        run("PDF 파일을 검사하고 있습니다", {
            val bytes = a.contentResolver.openInputStream(uri)?.use { PdfIntegrity.readLimited(it) }
                ?: error("파일을 열지 못했습니다")
            PdfIntegrity(a).verify(bytes)
        }) { result ->
            selected = null
            showReport(result)
        }
    }

    private fun inspect(pdf: ByteArray) {
        run("PDF 증명을 확인하고 있습니다", { PdfIntegrity(a).verify(pdf) }, ::showReport)
    }

    private fun showReport(result: Result<PdfIntegrity.Report>) {
        val report = result.getOrNull()
        val message = when (report?.status) {
            PdfIntegrity.Status.VALID -> {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.KOREAN).format(Date(report.time!!))
                "파일 변경 없음\nFreeTSA 서명·인증서 연결 확인\n기관 시각: $time\n\n인증서 폐기 상태와 실제 운행 사실은 확인하지 않습니다."
            }
            PdfIntegrity.Status.UNSIGNED -> "증명 없음\nPDF 문서 타임스탬프가 없습니다. 변조 여부를 판단할 수 없습니다."
            PdfIntegrity.Status.INVALID -> "검증 실패\n파일이 변경됐거나 손상됐거나 증명이 올바르지 않습니다."
            PdfIntegrity.Status.UNTRUSTED -> "기관 확인 불가\nFreeTSA 인증서로 확인할 수 없거나 지원하지 않는 서명 형식입니다. 변조로 단정하지 않습니다."
            null -> "파일을 검사하지 못했습니다. 접근 권한과 파일 크기(최대 32MB)를 확인해 주세요."
        }
        a.dialog("PDF 파일 검증", a.text(message, 17f, C.SUB).apply {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        }, null) {}
    }

    private fun <T> run(label: String, task: () -> T, done: (Result<T>) -> Unit) {
        if (!a.requireAdmin()) return
        if (busy) { a.toast("PDF 처리 중입니다. 잠시 후 다시 시도하세요"); return }
        busy = true
        val epoch = a.adminUiEpoch
        val progress = a.dialog(label, a.text("잠시 기다려 주세요", 17f, C.SUB).apply {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        }, null) {}
        worker.execute {
            val result = runCatching(task)
            a.runOnUiThread {
                busy = false
                val visible = progress.isShowing
                progress.dismiss()
                if (visible && epoch == a.adminUiEpoch && a.adminForeground &&
                    a.controller.canManage && !a.isDestroyed) done(result)
            }
        }
    }

    fun close() { selected = null; worker.shutdownNow() }

    companion object { const val REQUEST_PDF = 44 }
}
