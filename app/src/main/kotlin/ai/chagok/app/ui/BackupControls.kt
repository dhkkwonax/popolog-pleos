package ai.chagok.app.ui

import ai.chagok.app.MainActivity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native settings composition: quiet chrome, real record counts, one primary action. */
fun MainActivity.backupControls(): View {
    val b = backup
    if (b.saleState.isNotEmpty() || controller.dataSuspended) return saleMaintenance()
    val error = b.message.contains("실패")
    val state = when {
        b.busy -> "기록을 보관하고 있어요"
        error -> "백업을 확인해 주세요"
        !b.enabled -> "아직 백업이 없어요"
        b.pending -> "새 기록 백업 대기 중"
        else -> "기록이 백업되어 있어요"
    }
    val last = if (b.lastSuccess == 0L) "아직 완료된 백업이 없습니다" else
        "마지막 완료  " + SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(b.lastSuccess))
    val summary = vbox {
        add(text("기록 백업·복구", 20f, bold = true))
        add(text("앱을 지워도 차량 기록을\n다시 불러올 수 있도록 보관하세요.", 16f, C.SUB), top = 10)
        add(text("운행 ${controller.trips.size}건 · 비용 ${controller.expenses.size}건", 17f, tabular = true), top = 22)
        add(text("출발·도착 좌표와 검증 이력도 함께 보관합니다.", 14f, C.SUB), top = 6)
    }
    val controls = vbox {
        background = rounded(C.SURFACE2, 8)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        add(text(state, 20f, if (error) C.RED else C.TEXT, bold = true))
        add(text(last, 14f, C.SUB, tabular = true), top = 8)
        if (b.message.isNotBlank() && !b.busy) add(text(b.message, 14f, if (error) C.RED else C.SUB), top = 8)
        add(backupAction(if (b.enabled) "지금 백업하기" else "백업 폴더 설정", primary = true, enabled = !b.busy) {
            if (b.enabled) b.retry() else chooseBackupFolder("backup")
        }, top = 20)
        if (b.enabled) add(backupAction("백업 폴더 변경", enabled = !b.busy) { chooseBackupFolder("backup") }, top = 8)
        add(backupAction("백업에서 복구", enabled = !b.busy) { chooseBackupFolder("restore") }, top = 8)
        if (b.restoreFolderSelected) add(backupAction("선택한 폴더의 복구 파일 보기", enabled = !b.busy) { showBackupCandidates() }, top = 8)
        // 데모 빌드 전용: 에뮬레이터에 문서 선택기가 없어 고정 폴더로 백업·복구를 시험한다
        if (ai.chagok.app.BuildConfig.DEMO) {
            add(backupAction("데모 고정 폴더로 백업", enabled = !b.busy) {
                runCatching { b.selectDemoFolder(false); b.retry() }.onFailure { android.util.Log.w("Popolog", "demo folder: ${it.message}"); toast(it.message ?: "데모 폴더 설정 실패") }
            }, top = 8)
            add(backupAction("데모 고정 폴더에서 복구", enabled = !b.busy) {
                runCatching { b.selectDemoFolder(true); showBackupCandidates() }.onFailure { android.util.Log.w("Popolog", "demo folder: ${it.message}"); toast(it.message ?: "데모 폴더 설정 실패") }
            }, top = 8)
        }
        add(text("재설치 후에는 백업 폴더를 다시 선택해 주세요.", 14f, C.SUB), top = 12)
        if (b.purgePending && !b.busy) {
            add(text("운행 삭제의 원문 파기가 끝나지 않았어요 (삭제 미완료)", 14f, C.RED), top = 16)
            add(backupAction("원문 파기 재시도", destructive = true) { b.purgeCopies { toast(it) } }, top = 8)
        }
    }
    return card {
        // Available width = label 38% + 24dp gap + controls 62%; narrow windows stack.
        if (isNarrow) { add(summary); add(controls, top = 20) }
        else add(hbox(Gravity.TOP) {
            add(summary, 0, WRAP, 0.38f, end = 24)
            add(controls, 0, WRAP, 0.62f)
        })
        add(text("백업은 선택한 기기 내 폴더에 관리자 PIN·복구 코드로 암호화해 저장합니다. 다른 앱이 파일을 열어도 내용은 읽을 수 없지만, 6자리 PIN은 파일을 확보한 사람의 대입 공격까지 막지는 못합니다. 기기 초기화나 직접 파일 삭제 시에는 기록이 사라질 수 있습니다.", 14f, C.SUB), top = 20)
    }
}

fun MainActivity.saleControls(): View = card {
    add(text("차량 매각 전 데이터 정리", 20f, bold = true))
    add(text("차량을 넘기기 전에 운행·비용 기록과 백업, 내보낸 파일을 모두 지웁니다. 보존기간이 남은 기록도 삭제되며 복구할 수 없습니다.", 16f, C.SUB), top = 10)
    add(backupAction("차량 데이터 전체 삭제", destructive = true, enabled = !backup.busy) { confirmSaleDeletion() }, WRAP, WRAP, top = 18)
    add(text("관리자 PIN 재확인 후 삭제합니다. 외부로 공유·복사한 파일은 직접 관리해 주세요.", 14f, C.SUB), top = 12)
}

private fun MainActivity.saleMaintenance(): View = card {
    val b = backup
    val done = b.saleState == "done"
    add(text(if (done) "차량 데이터 삭제 완료" else if (b.saleState == "pending") "차량 데이터 삭제 확인" else "기록 복구 중", 22f, bold = true))
    add(text(if (b.busy) "작업이 끝날 때까지 차량 기록을 잠시 중단합니다." else b.message.ifBlank {
        if (done) "이 차량의 기록을 중단했습니다. 새 차량에서 사용할 때 다시 시작해 주세요." else "전체 삭제가 끝날 때까지 기록과 복구를 중단합니다."
    }, 16f, C.SUB), top = 12)
    if (b.saleState == "pending" && !b.busy) {
        add(text("이전 백업 폴더와 Download/Popolog의 접근을 허용해 주세요. 폴더가 없다면 선택기에서 만들 수 있습니다.", 14f, C.SUB), top = 16)
        add(backupAction("삭제 대상 폴더 선택") { chooseBackupFolder("sale") }, top = 20)
        add(backupAction("전체 삭제 재시도", destructive = true) { b.deleteForSale() }, top = 8)
    }
    if (done) add(backupAction("새 차량 사용 시작", primary = true) { b.beginNewUse() }, WRAP, WRAP, top = 20)
}

private fun MainActivity.backupAction(label: String, primary: Boolean = false, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    button(label, enabled = enabled, onClick = onClick).apply {
        val fg = if (destructive) C.RED else if (primary && enabled) C.SURFACE else C.TEXT
        val fill = if (primary && enabled) C.TEXT else Color.TRANSPARENT
        setTextColor(if (enabled) fg else C.SUB)
        alpha = 1f
        background = pressable(rounded(fill, 8, if (destructive) C.RED else if (!primary) C.DIM else null))
        isFocusable = true
    }
