package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.Retention
import ai.chagok.app.model.Trip
import android.text.InputType
import android.view.View
import android.widget.TextView

/** 제출 확인·보존기간·만료 알림. 보호 화면(세무)에서만 쓰므로 관리자 잠금 해제 상태다 */
fun MainActivity.submissionCard(year: Int): View = card {
    val c = controller
    add(text("제출 확인·보존", 20f, bold = true))
    add(text("실제로 제출한 뒤 관리자가 확인하면 그날부터 보존기간을 셉니다. 확인한 기록은 수정·삭제할 수 없어요. 만료돼도 자동으로 지우지 않습니다.", 15f, C.SUB), top = 6)
    expiryBanner()?.let { add(it, top = 14) }
    add(hbox {
        add(text("보존기간 ${c.retentionYears}년 (최소 ${Retention.MIN_YEARS}년, 늘리기만 가능)", 16f, C.SUB), 0, WRAP, 1f)
        add(button("연장", BtnStyle.GHOST) { extendRetention() }, WRAP, WRAP)
    }, top = 14)
    if (c.submissions.isEmpty()) add(text("아직 제출 완료로 확인한 기록이 없어요", 16f, C.DIM), top = 14)
    c.submissions.forEach { s ->
        add(divider(), top = 14)
        val expired = Retention.expired(s, System.currentTimeMillis())
        add(text("${s.year}년 · 확인 ${ai.chagok.app.core.Integrity.dayOf(s.confirmedAt)}", 17f, bold = true), top = 14)
        add(text("운행 ${s.tripIds.size}건 · 비용 ${s.expenseIds.size}건" + (if (s.pdfName.isNotEmpty()) " · ${s.pdfName}" else ""), 14f, C.DIM), top = 4)
        add(text(
            "보존 종료 ${s.endDay}" + (if (expired) " · 만료됨" else "") +
                (if (s.statutoryEnd.isEmpty()) " · 법정 종료일 미확인" else " · 법정 종료일 ${s.statutoryEnd}"),
            15f, if (expired) C.AMBER else C.SUB, bold = expired,
        ), top = 4)
        add(button("법정 종료일 입력", BtnStyle.GHOST) { editStatutoryEnd(s) }, WRAP, WRAP, top = 6)
    }
    add(button("${year}년 제출 완료 확인", BtnStyle.PRIMARY) { confirmSubmissionDialog(year) }, WRAP, WRAP, top = 18)
}

/** 만료된 제출이 있으면 알림 한 줄. 없으면 null */
fun MainActivity.expiryBanner(): View? {
    val n = controller.expiredSubmissions().size
    if (n == 0) return null
    return text("보존기간이 만료된 제출 ${n}건이 있어요. 관리자가 확인한 뒤 별도로 정리하세요. 자동으로 지우지 않습니다.", 15f, C.AMBER, bold = true)
}

private fun MainActivity.extendRetention() {
    if (!requireAdmin()) return
    askAmountPad("보존기간 (년)", "현재 ${controller.retentionYears}년 · ${Retention.MIN_YEARS}년 미만으로 줄일 수 없어요", unit = "년") { v ->
        if (v < Retention.MIN_YEARS || v > 100) toast("${Retention.MIN_YEARS}년 이상 100년 이하로 입력해 주세요")
        else { controller.setRetentionYears(v.toInt()); render() }
    }
}

private fun MainActivity.editStatutoryEnd(s: Retention.Submission) {
    if (!requireAdmin()) return
    val input = editText("2031-03-31", InputType.TYPE_CLASS_TEXT, s.statutoryEnd)
    val body = vbox {
        setPadding(dp(24), dp(8), dp(24), 0)
        add(text("이 제출 기록에 적용되는 법정 보존 종료일을 yyyy-MM-dd 로 입력하세요. 비우면 미확인입니다. 미확인은 보존 의무가 없다는 뜻이 아니에요.", 15f, C.SUB))
        add(input, top = 12)
    }
    dialog("법정 보존 종료일", body, "저장") {
        if (controller.setStatutoryEnd(s.id, input.text.toString().trim())) render()
        else toast("날짜 형식을 확인해 주세요")
    }
}

private fun MainActivity.confirmSubmissionDialog(year: Int) {
    if (!requireAdmin()) return
    val (trips, expenses) = controller.submissionScope(year)
    val pdf = pdfFiles.lastSaved
    var attach = pdf != null
    val end = editText("법정 종료일 (선택) 2031-03-31", InputType.TYPE_CLASS_TEXT)
    val body = vbox {
        setPadding(dp(24), dp(8), dp(24), 0)
        add(text("${year}년 운행 ${trips.size}건 · 비용 ${expenses.size}건", 17f, bold = true))
        add(text("실제로 제출을 마쳤을 때만 확인하세요. PDF 저장·공유만으로는 제출로 보지 않습니다. 확인하면 오늘이 보존 기산일이 되고, 포함된 운행·비용은 수정·삭제할 수 없습니다.", 15f, C.SUB), top = 10)
        if (pdf != null) {
            val link = choice("방금 저장한 PDF 연결: ${pdf.first}", true) {}
            link.setOnClickListener { attach = !attach; link.text = (if (attach) "방금 저장한 PDF 연결: " else "PDF 연결 안 함: ") + pdf.first }
            add(link, top = 12)
        }
        add(end, top = 12)
    }
    dialog("제출 완료를 확인할까요?", body, "계속") {
        val day = end.text.toString().trim()
        if (!Retention.validDay(day)) { toast("법정 종료일 형식을 확인해 주세요"); return@dialog }
        reauthenticateAdmin {
            val err = controller.confirmSubmission(year, day, if (attach) pdf?.first.orEmpty() else "", if (attach) pdf?.second.orEmpty() else "")
            toast(err ?: "제출 완료를 확인했어요")
            render()
        }
    }
}

/** 미제출 운행 단건 삭제: 영향 안내 → PIN 재확인 → 정확한 `삭제` 입력 → 원문·사본 파기 */
fun MainActivity.confirmTripDeletion(t: Trip) {
    if (!requireAdmin()) return
    if (controller.isTripLocked(t.id)) { toast("제출 완료로 확인된 운행은 삭제할 수 없어요"); return }
    val scope = vbox {
        setPadding(dp(24), dp(8), dp(24), 0)
        add(text("${t.fromName.ifEmpty { "출발지" }} → ${t.toName.ifEmpty { "미등록 장소" }} · ${AppController.fmtKm(t.distanceKm)}", 17f, bold = true))
        add(text("이 운행의 이름·장소·메모와 정정 이력 원문을 바로 파기합니다. 되돌릴 수 없어요.", 16f), top = 12)
        add(text("운행기록부·업무거리·업무사용비율 집계가 달라지고, 이미 출력한 자료와 맞지 않을 수 있습니다. 증빙이 필요하면 먼저 확인하세요.", 15f, C.SUB), top = 12)
        add(text("앱이 관리하는 백업과 내보내기 파일에서도 지웁니다. 외부로 공유·복사한 파일과, 삭제 전 백업을 복구하면 다시 나타날 수 있는 기록은 직접 관리해야 합니다.", 14f, C.SUB), top = 12)
        add(text("원장은 다시 쓰지 않아 이 구간은 ‘원문 파기로 검증 불가’로 표시됩니다.", 14f, C.SUB), top = 12)
    }
    dialog("운행 한 건을 삭제할까요?", scope, "계속") {
        reauthenticateAdmin { showTripDeletionConfirm(t) }
    }
}

private fun MainActivity.showTripDeletionConfirm(t: Trip) {
    val input = editText("삭제", InputType.TYPE_CLASS_TEXT)
    val body = vbox {
        setPadding(dp(24), dp(8), dp(24), 0)
        add(text("확인 문구: 삭제", 16f, bold = true))
        add(input, top = 8)
    }
    val confirmation = dialog("삭제 최종 확인", body, "운행 삭제", destructive = true) {
        if (input.text.toString() != "삭제" || !requireAdmin()) { toast("입력이 일치하지 않아 취소했습니다"); return@dialog }
        val err = controller.deleteTrip(t.id)
        if (err != null) { toast(err); return@dialog }
        toast("운행을 삭제했어요. 백업·내보내기 사본을 정리하는 중입니다")
        render()
        backup.purgeCopies { toast(it) }
    }
    val submit = confirmation.window?.decorView?.findViewWithTag<TextView>("dialog-positive")
    fun setReady() {
        val ready = input.text.toString() == "삭제"
        submit?.apply {
            isEnabled = ready
            setTextColor(if (ready) C.SURFACE else C.SUB)
            background = pressable(rounded(if (ready) C.RED else C.SURFACE2, 8))
        }
    }
    setReady()
    input.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { setReady() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
}
