package ai.chagok.app

import ai.chagok.app.core.KoreanAmountParser
import ai.chagok.app.model.ClassifiedBy
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseSource
import ai.chagok.app.model.Powertrain
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import ai.chagok.app.ui.askAmountPad
import ai.chagok.app.ui.handleGleo
import ai.chagok.app.ui.showExpenseInput
import ai.chagok.app.ui.BtnStyle
import ai.chagok.app.ui.C
import ai.chagok.app.ui.DemoOverlay
import ai.chagok.app.ui.DragFrame
import ai.chagok.app.ui.Ic
import ai.chagok.app.ui.icon
import ai.chagok.app.ui.iconDrawable
import ai.chagok.app.ui.HomeScreen
import ai.chagok.app.ui.LedgerScreen
import ai.chagok.app.ui.adminGate
import ai.chagok.app.ui.requireAdmin
import ai.chagok.app.ui.reauthenticateAdmin
import ai.chagok.app.ui.LogbookScreen
import ai.chagok.app.ui.MATCH
import ai.chagok.app.ui.PlacesScreen
import ai.chagok.app.ui.backupControls
import ai.chagok.app.data.RecordBackup
import android.net.Uri
import android.provider.DocumentsContract
import ai.chagok.app.ui.SettingsScreen
import ai.chagok.app.ui.TaxScreen
import ai.chagok.app.ui.PdfControls
import ai.chagok.app.ui.WRAP
import ai.chagok.app.ui.add
import ai.chagok.app.ui.button
import ai.chagok.app.ui.chip
import ai.chagok.app.ui.dp
import ai.chagok.app.ui.hbox
import ai.chagok.app.ui.isNarrow
import ai.chagok.app.ui.pressable
import ai.chagok.app.ui.rounded
import ai.chagok.app.ui.sp
import ai.chagok.app.ui.text
import ai.chagok.app.ui.vbox
import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    lateinit var controller: AppController
        private set

    lateinit var backup: RecordBackup
        private set
    private var folderPurpose = "backup"
    val pdfFiles by lazy { PdfControls(this) }

    enum class Tab(val label: String, val icon: Ic) {
        HOME("홈", Ic.HOME),
        LEDGER("장부", Ic.LEDGER),
        LOGBOOK("운행일지", Ic.SWAP),
        TAX("세무 리포트", Ic.PERCENT),
        PLACES("장소", Ic.PIN),
        SETTINGS("설정", Ic.SLIDERS),
    }

    var tab = Tab.HOME
        private set

    /** 장부·운행일지 화면의 월 이동 (0 = 이번 달) */
    var monthOffset = 0

    private lateinit var content: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var stage: FrameLayout
    private lateinit var overlayHost: DragFrame

    /** 시연 오버레이 펼침 여부. 기본은 접힌 작은 버튼 */
    private var demoExpanded = false

    /** 시연자가 끌어 옮긴 오버레이 위치(창 대비 비율). 없으면 빈 곳을 찾아 놓는다 */
    private var overlayPos: Pair<Float, Float>? = null
    private lateinit var rail: LinearLayout
    private lateinit var statusBar: LinearLayout
    private var renderedKey = ""
    var adminUiEpoch = 0L
        private set
    var adminForeground = false
        private set
    private var observedAdminGeneration = 0L
    private val openDialogs = mutableListOf<Dialog>()

    fun lockAdmin() {
        controller.admin.lock()
        invalidateAdminUi()
        if (::content.isInitialized) render()
    }

    private fun invalidateAdminUi() {
        adminUiEpoch++
        openDialogs.toList().forEach { it.dismiss() }
        observedAdminGeneration = controller.admin.generation
    }

    override fun onResume() {
        super.onResume()
        adminForeground = true
    }

    override fun onPause() {
        adminForeground = false
        lockAdmin()
        super.onPause()
    }

    private val onChanged: () -> Unit = {
        if (observedAdminGeneration != controller.admin.generation) invalidateAdminUi()
        updateStatus()
        val key = dataKey()
        if (key != renderedKey) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        folderPurpose = savedInstanceState?.getString("backup_folder_purpose") ?: "backup"
        controller = AppController(this)
        backup = RecordBackup(this, controller) { if (::content.isInitialized) render() }
        controller.typedInputHandler = ::speechSimDialog
        controller.flows.openExpenseInput = { draft -> showExpenseInput(draft) }
        applyDayNight()
        // 스크린샷 자동 촬영용 빨리 감기 (am start --ei sim_speed N). 가상 차량에만 영향
        ai.chagok.app.vehicle.SimulatedVehicle.timeScale = intent.getIntExtra("sim_speed", 1).coerceIn(1, 8)
        buildShell()
        controller.addListener(onChanged)
        requestCarPermissions()
        controller.start()
        backup.start()
        render()
        window.decorView.post { if (!controller.dataSuspended) handleGleo(intent) }
        // 실제 동작 모드는 처음 실행할 때 개인정보 처리 안내를 한 번 보여 준다 (설정에서 다시 볼 수 있다)
        if (!controller.dataSuspended && !controller.demo && controller.db.setting(AppController.KEY_PRIVACY_ACK) != "1") {
            window.decorView.post { showPrivacyNotice(firstRun = true) }
        }
    }

    fun chooseBackupFolder(purpose: String) {
        if (backup.busy || controller.isDriving) { toast("정차 후 작업이 끝나면 다시 시도하세요"); return }
        folderPurpose = purpose
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
        try { startActivityForResult(picker, REQ_BACKUP_FOLDER) }
        catch (_: ActivityNotFoundException) { toast("이 기기는 폴더 선택기를 제공하지 않아 백업을 설정할 수 없습니다") }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("backup_folder_purpose", folderPurpose)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PdfControls.REQUEST_PDF) {
            if (resultCode == RESULT_OK) data?.data?.let(pdfFiles::onFileSelected)
            return
        }
        if (requestCode != REQ_BACKUP_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            require(uri.authority == "com.android.externalstorage.documents") { "기기 내 폴더를 선택하세요" }
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            backup.selectFolder(uri, folderPurpose == "sale", folderPurpose != "restore")
            if (folderPurpose == "restore") showBackupCandidates()
            else if (folderPurpose != "sale") backup.retry()
            render()
        } catch (e: Exception) { toast(e.message ?: "폴더 접근 권한이 필요합니다") }
    }

    fun showBackupCandidates() {
        if (!requireAdmin()) return
        backup.candidates { docs ->
            if (!controller.canManage || !adminForeground) return@candidates
            if (docs.isEmpty()) { toast("완료된 백업이 없습니다"); return@candidates }
            val body = vbox {
                setPadding(dp(24), 0, dp(24), 0)
                docs.forEachIndexed { i, doc ->
                    add(button("백업 ${i + 1} 복구", BtnStyle.SECONDARY) {
                        dialog("전체 기록을 교체할까요?", text("현재 기록은 선택한 백업으로 교체됩니다. 병합하지 않습니다. 취소하면 현재 기록을 유지합니다.", 16f, C.SUB), "복구") {
                            if (controller.isDriving) toast("정차 후 복구하세요") else backup.restore(doc)
                        }
                    }, top = 8)
                }
            }
            dialog("완료된 백업 · 최신순", ScrollView(this).apply { addView(body) }, null) {}
        }
    }

    fun confirmSaleDeletion() {
        if (!requireAdmin()) return
        reauthenticateAdmin { showSaleDeletion() }
    }

    private fun showSaleDeletion() {
        if (backup.busy || controller.isDriving) { toast("정차 후 작업이 끝나면 다시 시도하세요"); return }
        val scope = vbox {
            setPadding(dp(24), dp(8), dp(24), 0)
            add(text(controller.gateway.info.title, 17f, bold = true))
            add(text("운행·비용·좌표·검증 이력과 백업, 앱이 만든 내보내기 파일을 모두 삭제합니다.", 16f), top = 12)
            add(text("보존기간이 남은 기록도 삭제됩니다. 필요한 세무 증빙은 먼저 별도로 보관해 주세요.", 15f, C.SUB), top = 12)
            add(text("삭제한 기록은 복구할 수 없습니다. 외부로 공유·복사한 파일은 직접 관리해야 합니다.", 14f, C.SUB), top = 12)
        }
        dialog("차량 기록을 모두 지울까요?", scope, "계속", destructive = false) {
            val input = editText("삭제", InputType.TYPE_CLASS_TEXT)
            val body = vbox {
                setPadding(dp(24), dp(8), dp(24), 0)
                add(text("이 차량의 기록과 백업을 모두 지웁니다.\n이 작업은 되돌릴 수 없습니다.", 16f))
                add(text("확인 문구: 삭제", 16f, bold = true), top = 20)
                add(input, top = 8)
            }
            val confirmation = dialog("전체 삭제 최종 확인", body, "모든 데이터 삭제", destructive = true) {
                if (input.text.toString() == "삭제" && requireAdmin() && controller.admin.recentlyVerified()) {
                    runCatching { backup.deleteForSale() }.onFailure { toast(it.message ?: "삭제 시작 실패") }
                } else toast("입력이 일치하지 않아 취소했습니다")
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
    }

    /** 이미 떠 있는 앱으로 Gleo 의 함수 호출이 오는 경우 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!controller.dataSuspended) handleGleo(intent)
    }

    /** 개인정보 처리 안내 (초안). 처음 실행이면 확인 버튼으로 확인 기록을 남긴다 */
    fun showPrivacyNotice(firstRun: Boolean = false) {
        val body = vbox {
            setPadding(dp(24), dp(4), dp(24), dp(4))
            ai.chagok.app.core.PrivacyNotice.sections(
                controller.db.setting(AppController.KEY_COMPANY), controller.db.setting(AppController.KEY_PRIVACY_CONTACT),
            ).forEachIndexed { i, s ->
                add(text(s.title, 17f, bold = true), top = if (i == 0) 0 else 16)
                add(text(s.body, 15f, C.SUB), top = 6)
            }
        }
        val scroll = ScrollView(this).apply { addView(body) }
        // 음성은 안내를 본 뒤 고른다: 확인만 하면 음성 없이 화면 입력으로 쓰고, 설정에서 바꿀 수 있다
        var dlg: Dialog? = null
        val extra = if (firstRun && controller.voiceUndecided) listOf("음성도 사용" to {
            controller.db.setSetting(AppController.KEY_PRIVACY_ACK, "1")
            controller.setVoiceEnabled(true)
            dlg?.dismiss()
            Unit
        }) else emptyList()
        val d = dialog("개인정보 처리 안내", scroll, if (firstRun) (if (controller.voiceUndecided) "확인했어요 (음성 없이)" else "확인했어요") else null, extra) {
            controller.db.setSetting(AppController.KEY_PRIVACY_ACK, "1")
            if (controller.voiceUndecided) controller.setVoiceEnabled(false)
        }
        dlg = d
        // 긴 글이 화면 밖으로 나가지 않게 높이를 제한한다
        scroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6).toInt())
        d.setCancelable(!firstRun)
    }

    /**
     * Pleos 빌드에서 FusedLocation 권한은 protectionLevel="dangerous" 라 런타임 승인이 필요하다.
     * 시뮬레이터 빌드에서는 선언되지 않은 권한이라 요청할 것이 없다.
     */
    private fun requestCarPermissions() {
        val needed = CAR_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_CAR_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 승인 전에는 위치 없이 붙어 있으므로, 승인되면 위치까지 포함해 다시 연결한다
        if (requestCode == REQ_CAR_PERMISSIONS &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        ) {
            controller.reconnectGateway()
        }
    }

    /** 회전·분할 화면 등으로 가로/세로가 바뀌면 내비게이션 배치를 다시 만든다 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 주간·야간이 바뀌면 (Pleos 시스템 UI 와 같은 신호) 색을 바꿔 바로 다시 그린다
        applyDayNight()
        buildShell()
        render()
    }

    /** 설정의 화면 테마: 자동(시스템 주야간 신호) · 주간 · 야간 */
    private fun applyDayNight() {
        C.use(
            when (controller.db.setting(AppController.KEY_THEME, THEME_AUTO)) {
                THEME_DAY -> false
                THEME_NIGHT -> true
                else -> C.isNight(this)
            },
        )
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        window.setBackgroundDrawable(ColorDrawable(C.BG))
    }

    override fun onDestroy() {
        pdfFiles.close()
        controller.removeListener(onChanged)
        controller.stop()
        backup.close()
        super.onDestroy()
    }

    /** 테마를 바꾸고 바로 다시 그린다 */
    fun applyThemeMode(mode: String) {
        if (controller.dataSuspended) return
        controller.db.setSetting(AppController.KEY_THEME, mode)
        applyDayNight()
        buildShell()
        render()
    }

    private fun isWide() = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

    private fun buildShell() {
        val wide = isWide()
        val root = if (wide) hbox(Gravity.TOP) else vbox()
        root.setBackgroundColor(C.BG)

        rail = if (wide) vbox(Gravity.TOP) else hbox(Gravity.CENTER)
        rail.setBackgroundColor(C.RAIL)
        if (wide) {
            rail.setPadding(dp(12), dp(20), dp(12), dp(20))
            rail.add(logo(), MATCH, WRAP)
        } else {
            rail.setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val main = vbox()
        statusBar = hbox()
        statusBar.setPadding(dp(28), dp(18), dp(28), dp(10))
        main.add(statusBar)
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        content = FrameLayout(this)
        scroll.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        main.add(scroll, MATCH, 0, 1f)

        // 본문 위에 시연 오버레이를 띄우는 층. 본문 배치에는 끼어들지 않는다
        stage = FrameLayout(this)
        stage.addView(main, FrameLayout.LayoutParams(MATCH, MATCH))
        overlayHost = DragFrame(this,
            onDrag = { dx, dy -> moveOverlayTo(overlayLp().leftMargin + dx.toInt(), overlayLp().topMargin + dy.toInt()) },
            onDragEnd = {
                val p = overlayLp().leftMargin.toFloat() / stage.width to overlayLp().topMargin.toFloat() / stage.height
                overlayPos = p
                if (!controller.dataSuspended) controller.db.setSetting(KEY_OVERLAY_POS, "${p.first},${p.second}")
            },
        )
        stage.addView(overlayHost, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        overlayPos = controller.db.setting(KEY_OVERLAY_POS).split(",").mapNotNull { it.toFloatOrNull() }
            .takeIf { it.size == 2 }?.let { it[0] to it[1] }

        if (wide) {
            // 아이콘 + 메뉴이름 한 줄이 들어가도록 폭은 내용에 맞춘다 (가장 긴 "세무 리포트" 기준)
            root.add(rail, WRAP, MATCH)
            root.add(stage, 0, MATCH, 1f)
        } else {
            root.add(stage, MATCH, 0, 1f)
            root.add(rail, MATCH, WRAP)
        }
        setContentView(root)
    }

    private fun renderOverlay() {
        overlayHost.removeAllViews()
        if (controller.dataSuspended) { overlayHost.visibility = View.GONE; return }
        val v = DemoOverlay(this).render(demoExpanded) {
            demoExpanded = !demoExpanded
            renderOverlay()
        } ?: return
        overlayHost.addView(v, FrameLayout.LayoutParams(WRAP, WRAP))
        // 본문이 다 놓인 뒤에야 빈 곳을 잴 수 있다. 자리를 잡기 전에는 왼쪽 위(0,0)에 잠깐 떠서
        // 본문을 가리거나 잘못 눌릴 수 있으므로, 자리를 잡을 때까지 숨겨 둔다
        overlayHost.visibility = View.INVISIBLE
        stage.post { if (!controller.dataSuspended) { placeOverlay(); overlayHost.visibility = View.VISIBLE } }
    }

    private fun overlayLp() = overlayHost.layoutParams as FrameLayout.LayoutParams

    private fun placeOverlay() {
        if (overlayHost.childCount == 0 || stage.width == 0) return
        overlayHost.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val saved = overlayPos
        val (x, y) = if (saved != null) {
            (saved.first * stage.width).toInt() to (saved.second * stage.height).toInt()
        } else {
            emptySpot(overlayHost.measuredWidth, overlayHost.measuredHeight)
        }
        moveOverlayTo(x, y)
    }

    /** 창 밖으로 나가지 않게 잘라서 옮긴다 */
    private fun moveOverlayTo(x: Int, y: Int) {
        // 접힌 크기로 남아 있는 width 대신, 방금 잰 펼친 크기까지 고려해 창 밖으로 나가지 않게 한다
        val w = maxOf(overlayHost.width, overlayHost.measuredWidth)
        val h = maxOf(overlayHost.height, overlayHost.measuredHeight)
        overlayLp().apply {
            leftMargin = x.coerceIn(0, maxOf(0, stage.width - w))
            topMargin = y.coerceIn(0, maxOf(0, stage.height - h))
        }
        overlayHost.requestLayout()
    }

    /**
     * 본문을 가리지 않는 자리를 찾는다.
     * 1) 머리말 줄(제목·차종·칩)에서 글자와 칩이 없는 가장 오른쪽 자리
     *    - 넓은 창: 제목과 칩 사이 빈칸 / 좁은 창: 칩이 제목 아래로 내려가 비는 오른쪽 위
     * 2) 본문이 짧아 아래가 비면 그 아래
     * 3) 둘 다 없으면 오른쪽 아래 구석
     */
    private fun emptySpot(w: Int, h: Int): Pair<Int, Int> {
        val m = dp(12)
        val stageLoc = IntArray(2).also { stage.getLocationInWindow(it) }
        fun rectOf(v: View): android.graphics.Rect {
            val loc = IntArray(2).also { v.getLocationInWindow(it) }
            val l = loc[0] - stageLoc[0]
            val t = loc[1] - stageLoc[1]
            return android.graphics.Rect(l, t, l + v.width, t + v.height)
        }
        // 화면이 자리를 정해 둔 경우 (홈: 차량 카드 제목 줄 오른쪽). 제목 글자와 겹치지 않을 때만 쓴다
        (content.findViewWithTag<LinearLayout>(ai.chagok.app.ui.HomeScreen.OVERLAY_SLOT))?.let { slot ->
            val r = rectOf(slot)
            val title = slot.getChildAt(0) as? TextView
            val textRight = r.left + (title?.let { it.paint.measureText(it.text.toString()).toInt() } ?: 0)
            val x = r.right - w
            val y = r.top + (r.height() - h) / 2
            if (x - m > textRight && y >= 0) return x to y
        }
        // 머리말에서 실제로 글자·칩이 그려진 칸들 (제목 묶음은 폭을 다 차지하므로 그 안의 자식을 본다)
        val taken = mutableListOf<android.graphics.Rect>()
        for (i in 0 until statusBar.childCount) {
            val child = statusBar.getChildAt(i)
            if (child is LinearLayout && child.orientation == LinearLayout.VERTICAL) {
                for (j in 0 until child.childCount) taken += rectOf(child.getChildAt(j))
            } else {
                taken += rectOf(child)
            }
        }
        val band = rectOf(statusBar)
        val y = band.top + maxOf(m / 2, (band.height() - h) / 2)
        if (y + h <= band.bottom) {
            var x = stage.width - m - w
            while (x >= m) {
                val r = android.graphics.Rect(x - m, y, x + w + m, y + h)
                if (taken.none { android.graphics.Rect.intersects(it, r) }) return x to y
                x -= dp(8)
            }
        }
        val body = content.getChildAt(0)?.let { rectOf(it) }
        if (body != null && body.bottom + m + h <= stage.height - m) return stage.width - m - w to body.bottom + m
        return stage.width - m - w to stage.height - m - h
    }

    private fun logo(): View = hbox(Gravity.CENTER_VERTICAL) {
        add(ImageView(context).apply {
            // res/mipmap/ic_launcher.png (tools/arsc.py 규칙으로 0x7f010000)
            try {
                setImageResource(R.mipmap.ic_launcher)
            } catch (e: android.content.res.Resources.NotFoundException) {
                setImageDrawable(rounded(C.ACCENT_BG, 14))
            }
        }, dp(48), dp(48), end = 14)
        add(text("Popolog", 22f, bold = true), WRAP, WRAP)
        setPadding(dp(10), dp(4), 0, dp(20))
    }

    private fun renderRail() {
        val wide = isWide()
        val keep = if (wide) 1 else 0
        while (rail.childCount > keep) rail.removeViewAt(keep)
        for (t in Tab.values()) {
            val selected = t == tab
            if (wide) {
                rail.add(railRow(t, selected), MATCH, WRAP, top = 6)
                continue
            }
            // 1칸 아래 탭: 선택은 강조색이 아닌 회색 알약과 흰 글자
            val item = vbox(Gravity.CENTER) {
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = pressable(rounded(if (selected) C.NAV_ACTIVE else Color.TRANSPARENT, 16))
                add(icon(t.icon, if (selected) C.TEXT else C.SUB, 22), WRAP, WRAP)
                add(text(t.label, 12f, if (selected) C.TEXT else C.SUB, bold = selected).apply { gravity = Gravity.CENTER }, MATCH, WRAP, top = 4)
                contentDescription = t.label
                setOnClickListener { select(t) }
            }
            rail.add(item, 0, WRAP, 1f)
        }
    }

    /** 2칸(가로) 창의 왼쪽 메뉴 한 줄: 아이콘 오른쪽에 메뉴이름을 본문 제목 크기로. 선택된 줄은 전체 배경으로 강조 */
    private fun railRow(t: Tab, selected: Boolean): View = hbox(Gravity.CENTER_VERTICAL) {
        setPadding(dp(16), dp(16), dp(24), dp(16))
        // 선택된 줄은 강조색이 아닌 중립 회색 알약 (줄 전체)
        background = pressable(rounded(if (selected) C.NAV_ACTIVE else Color.TRANSPARENT, 16))
        add(icon(t.icon, if (selected) C.TEXT else C.SUB, 26), WRAP, WRAP, end = 16)
        add(text(t.label, 20f, if (selected) C.TEXT else C.SUB, bold = selected, lines = 1), WRAP, WRAP)
        contentDescription = t.label
        setOnClickListener { select(t) }
    }

    fun select(t: Tab) {
        if (t != tab) monthOffset = 0
        tab = t
        render()
        scroll.scrollTo(0, 0)
    }

    private fun dataKey(): String = with(controller) {
        listOf(
            dataSuspended, canManage, admin.configured, tab, trips.size, trips.firstOrNull()?.let { it.id to it.purpose }, expenses.size,
            expenses.count { it.pendingAmount }, expenses.sumOf { it.amountWon }, places.size,
            askTripId, askExpenseId, monthOffset, powertrain, lastNotice,
            driver?.driver?.id, driver?.method, driverGreeting, drivers,
            assistant.current?.id, assistant.status, assistant.listening, flows.syncReview().size, flows.dueItems().size,
            (gateway as? ai.chagok.app.vehicle.SimulatedVehicle)?.busy,
            // 홈은 차량 카드(주행 중·누적·연료)가 화면 안에 있어 값이 바뀌면 다시 그린다. 다른 탭은 updateStatus 가 맡는다
            if (tab == Tab.HOME) listOf(isDriving, odoKm.toInt(), Math.round(energyPercent)) else null,
        ).joinToString("|")
    }

    fun render() {
        observedAdminGeneration = controller.admin.generation
        renderedKey = dataKey()
        renderRail()
        updateStatus()
        renderOverlay()
        content.removeAllViews()
        val view = if (controller.dataSuspended) backupControls()
        else if ((tab == Tab.LOGBOOK || tab == Tab.TAX || tab == Tab.LEDGER) && !controller.canManage) adminGate()
        else when (tab) {
            Tab.HOME -> HomeScreen(this).render()
            Tab.LEDGER -> LedgerScreen(this).render()
            Tab.LOGBOOK -> LogbookScreen(this).render()
            Tab.TAX -> TaxScreen(this).render()
            Tab.PLACES -> PlacesScreen(this).render()
            Tab.SETTINGS -> SettingsScreen(this).render()
        }
        view.setPadding(dp(28), dp(8), dp(28), dp(36))
        content.addView(view, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun updateStatus() {
        val c = controller
        statusBar.removeAllViews()
        // 홈은 페이지 제목·상태 줄 대신 차량·운전자 카드(HomeScreen)가 맨 위에 온다
        statusBar.visibility = if (tab == Tab.HOME) View.GONE else View.VISIBLE
        if (tab == Tab.HOME) return
        // 제목 24sp. 차량 상태는 칩 대신 회색 글자 한 줄로 차종 옆(좁은 창은 아래 줄)에 둔다
        val ev = c.gateway.info.powertrain == Powertrain.EV
        val state = listOf(
            if (c.isDriving) "주행 중 " + AppController.fmtKm(c.liveTripKm) else "P 주차",
            "계기판 " + AppController.fmtKm(c.odoKm),
            (if (ev) "배터리 " else "연료 ") + Math.round(c.energyPercent) + "%",
        ).joinToString(" · ")
        val title = vbox {
            add(text(tab.label, 24f, bold = true), WRAP, WRAP)
            if (isNarrow) {
                add(text(c.gateway.info.title, 15f, C.SUB, lines = 1), WRAP, WRAP, top = 4)
                add(text(state, 15f, if (c.isDriving) C.TEXT else C.SUB, lines = 1, tabular = true), WRAP, WRAP, top = 2)
            } else {
                add(text(c.gateway.info.title + " · " + state, 15f, C.SUB, lines = 1, tabular = true), WRAP, WRAP, top = 4)
            }
        }
        statusBar.add(title, 0, WRAP, 1f)
        // 칩은 하나만: 지금 운전석에 앉은 사람. 눌러서 바꿀 수 있다
        c.driver?.takeIf { !c.dataSuspended }?.let { d ->
            val who = chip("${d.driver.name} · ${d.method.label}", C.TEXT, C.SURFACE2, 15f).apply {
                setCompoundDrawablesRelative(iconDrawable(Ic.PERSON, C.SUB, 18), null, null, null)
                compoundDrawablePadding = dp(6)
                minHeight = dp(44)
                contentDescription = "운전자 바꾸기"
                setOnClickListener { pickDriver() }
            }
            statusBar.add(who, WRAP, WRAP)
        }
    }

    /** 운전자 고르기. 시동 때 자동으로 정해지지만 틀렸으면 여기서 바꾼다 */
    fun pickDriver() {
        if (controller.dataSuspended) return
        val list = vbox().apply { setPadding(dp(24), 0, dp(24), dp(8)) }
        lateinit var dlg: Dialog
        for (d in controller.drivers) {
            val current = controller.driver?.driver?.id == d.id
            list.add(choice("${d.name} · ${d.department}" + if (current) " · 지금 운전 중" else "", current) {
                controller.chooseDriver(d.id)
                dlg.dismiss()
            }, top = 10)
        }
        dlg = dialog("지금 운전하는 분", list, null) {}
    }

    // ---- 음성 입력 ----

    /**
     * 차량에서는 Pleos STT 로 듣고, 듣지 못하는 환경(시연 모드·에뮬레이터)에서는 말한 내용을 화면에 입력받는다.
     * 앱이 먼저 묻는 질문은 [AppController.assistant] 가 진행하고, 이 창은 그 대답(시연 응답)을 받는 자리다.
     */
    private fun speechSimDialog(question: String, onResult: (String?) -> Unit) {
        val input = editText("말한 내용을 입력", InputType.TYPE_CLASS_TEXT)
        val body = vbox {
            setPadding(dp(24), dp(8), dp(24), dp(0))
            add(text(question, 16f, C.TEXT))
            add(text("이 화면은 시연용이에요. 차량에서는 마이크로 바로 듣습니다.", 14f, C.SUB), top = 6)
            add(input, top = 14)
        }
        dialog("🎤 말하기(시연)", body, "확인") { onResult(input.text.toString().ifBlank { null }) }
    }

    /** 음성 대화가 아닌 곳에서 짧게 듣기가 필요할 때 */
    fun listen(prompt: String, hint: String, onText: (String) -> Unit) {
        controller.speak(prompt)
        controller.voice.listen(prompt) { said -> said?.let(onText) }
    }

    fun askAmountByKeypad(e: Expense) {
        if (!controller.canEditExpense(e.id)) { requireAdmin(); return }
        val detail = e.quantity?.let { "감지량 약 ${AppController.fmt1(it)}${if (e.category.name == "CHARGE") "kWh" else "L"} · ${e.memo}" }.orEmpty()
        askAmountPad("${e.category.label} 금액 입력", detail) { v -> controller.setExpenseAmount(e.id, v, ExpenseSource.MANUAL) }
    }

    fun choosePurpose(t: Trip) {
        if (controller.isTripLocked(t.id)) { toast("제출 완료로 확인된 운행은 변경할 수 없어요"); return }
        if (!controller.canEditTrip(t.id)) { requireAdmin(); return }
        val options = TripPurpose.values().filter { it != TripPurpose.UNCLASSIFIED }
        var dlg: Dialog? = null
        val body = vbox {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            add(text("${t.fromName.ifEmpty { "출발지" }} → ${t.toName.ifEmpty { "미등록 장소" }} · ${AppController.fmtKm(t.distanceKm)}", 16f, C.SUB))
            for (p in options) {
                add(choice(p.label, p == t.purpose) {
                    controller.classifyTrip(t.id, p, ClassifiedBy.MANUAL)
                    dlg?.dismiss()
                }, top = 10)
            }
        }
        dlg = dialog("운행 목적", body, null) {}
    }

    /** 세무 운행기록부에 기재할 차량 이용자를 운행별로 확인한다. */
    fun chooseLogbookUser(t: Trip) {
        if (controller.isTripLocked(t.id)) { toast("제출 완료로 확인된 운행은 변경할 수 없어요"); return }
        if (!controller.canEditTrip(t.id)) { requireAdmin(); return }
        if (controller.drivers.isEmpty()) {
            toast("설정에서 차량 이용자를 먼저 등록해 주세요")
            select(Tab.SETTINGS)
            return
        }
        var dlg: Dialog? = null
        val body = vbox {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            add(text("운행을 실제로 이용한 사람을 선택하세요. 자동 추정값은 세무 서식에 쓰지 않습니다.", 15f, C.SUB))
            controller.drivers.forEach { user ->
                add(choice("${user.department} · ${user.name}", t.logbookUser == user.name && t.logbookDepartment == user.department) {
                    controller.confirmLogbookUser(t.id, user)
                    dlg?.dismiss()
                }, top = 10)
            }
        }
        val extras: List<Pair<String, () -> Unit>> = if (t.logbookUser.isNotBlank()) listOf("확인 해제" to {
            controller.confirmLogbookUser(t.id, null)
            dlg?.dismiss()
            Unit
        }) else emptyList()
        dlg = dialog("운행기록 사용자 확인", body, null, extras) {}
    }

    // ---- 공용 다이얼로그 ----

    fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    fun editText(hint: String, type: Int, value: String = ""): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
        // 가로 화면에서 차량 키보드가 입력창을 전체 화면으로 덮지 않게 한다 (확인 버튼이 가려진다)
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        // 엔터·완료는 다음 칸으로 넘어가지 않고 키보드를 닫는다 (키보드가 대화상자의 확인 버튼을 가리지 않게)
        setOnEditorActionListener { v, _, _ -> hideKeyboard(v); true }
        setOnKeyListener { v, keyCode, ev ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                if (ev.action == android.view.KeyEvent.ACTION_UP) hideKeyboard(v)
                true
            } else {
                false
            }
        }
        setText(value)
        textSize = sp(20f)
        setTextColor(C.TEXT)
        setHintTextColor(C.DIM)
        background = rounded(C.SURFACE2, 12)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    /**
     * 앱과 같은 어두운 표면의 대화상자. 시스템 밝은 대화상자는 차량 화면에서 따로 놀았다.
     * 채운 버튼은 [positive] 하나뿐이고, 닫기는 글자 버튼이다.
     */
    fun dialog(title: String, body: View, positive: String?, extra: List<Pair<String, () -> Unit>> = emptyList(), destructive: Boolean = false, onOk: () -> Unit): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = vbox {
            background = rounded(C.SURFACE, 20, C.LINE)
            setPadding(0, dp(24), 0, dp(16))
            add(text(title, 22f, bold = true).apply { setPadding(dp(24), 0, dp(24), dp(8)) })
            add(body)
            add(hbox(Gravity.END or Gravity.CENTER_VERTICAL) {
                setPadding(dp(24), dp(16), dp(24), 0)
                add(button("닫기", BtnStyle.GHOST) { d.dismiss() }.apply { tag = "dialog-close" }, WRAP, WRAP)
                for ((label, run) in extra) {
                    add(button(label, BtnStyle.SECONDARY) { if (!controller.dataSuspended) run() }, WRAP, WRAP)
                        .also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
                }
                if (positive != null) {
                    add(button(positive, BtnStyle.PRIMARY) { d.dismiss(); if (!controller.dataSuspended) onOk() else toast("기록 작업이 중단되어 있습니다") }.apply {
                        tag = "dialog-positive"
                        if (destructive) {
                            setTextColor(C.SURFACE)
                            background = pressable(rounded(C.RED, 8))
                        }
                    }, WRAP, WRAP)
                        .also { (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10) }
                }
            })
        }
        d.setContentView(panel)
        // 바깥을 눌러 실수로 닫히지 않게 한다 (닫기 버튼이 있다)
        d.setCanceledOnTouchOutside(false)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(dp(640), (resources.displayMetrics.widthPixels * 0.9).toInt()), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
        openDialogs += d
        d.setOnDismissListener { openDialogs.remove(d) }
        d.show()
        return d
    }

    /** PDF 를 쪽별 그림으로 보여 주고(‹ › 로 넘김), [onSave] 로 저장한다. 임시 파일은 그릴 때만 만들고 바로 지운다 */
    fun showPdfPreview(title: String, pdf: ByteArray, caption: String, extra: List<Pair<String, () -> Unit>> = emptyList(), onSave: () -> Unit): Dialog? {
        if (!requireAdmin()) return null
        val win = resources.displayMetrics
        // 대화상자 안에 제목·버튼이 함께 들어가도록 높이 기준으로도 줄인다 (A4 가로 = 1.414:1)
        val w = minOf((win.widthPixels * 0.9).toInt() - dp(48), ((win.heightPixels * 0.62) * 1.414).toInt())
        val h = (w / 1.414).toInt()
        val image = android.widget.ImageView(this)
        val pageLabel = text("", 15f, C.SUB, tabular = true)
        var pageCount = 1
        var page = 0
        fun show(n: Int) {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val f = File(cacheDir, "preview.pdf").apply { writeBytes(pdf) }
            try {
                android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { r ->
                        pageCount = r.pageCount
                        page = n.coerceIn(0, pageCount - 1)
                        r.openPage(page).use { it.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    }
                }
            } finally {
                f.delete()
            }
            image.setImageBitmap(bmp)
            image.contentDescription = "운행기록부 PDF ${page + 1}쪽"
            pageLabel.text = "${page + 1} / ${pageCount}쪽"
        }
        show(0)
        val body = vbox {
            setPadding(dp(24), dp(4), dp(24), 0)
            image.background = rounded(Color.WHITE, 6, C.LINE)
            add(image, w, h)
            add(hbox(Gravity.CENTER_VERTICAL) {
                add(text(caption, 15f, C.SUB, tabular = true), 0, WRAP, 1f)
                add(pageLabel, WRAP, WRAP)
            }, top = 10)
        }
        val paging: List<Pair<String, () -> Unit>> = if (pageCount > 1) listOf(
            "‹" to { show(page - 1) },
            "›" to { show(page + 1) },
        ) else emptyList()
        val d = dialog(title, body, "PDF 저장", paging + extra, onOk = onSave)
        d.window?.setLayout(w + dp(48), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        return d
    }

    /** 운행기록부 PDF 미리보기에서 여는 별도 검증 페이지. 체인·앵커를 다시 계산해 일치 여부를 보여 준다. */
    fun showVerifyPage(qr: String, ledgerJson: String) {
        if (!requireAdmin()) return
        val web = android.webkit.WebView(this)
        web.settings.javaScriptEnabled = true
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                view.evaluateJavascript("window.loadLedger(" + org.json.JSONObject.quote(ledgerJson) + ")", null)
            }
        }
        web.loadUrl("file:///android_asset/verify.html#" + qr.substringAfter('?') + "&night=" + (if (C.night) 1 else 0))
        val win = resources.displayMetrics
        val d = dialog("검증 페이지", web, null) {}
        val w = minOf(dp(900), (win.widthPixels * 0.9).toInt())
        web.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (win.heightPixels * 0.62).toInt())
        d.window?.setLayout(w, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** 대화상자의 선택지: 모두 같은 모양, 지금 값만 ✓ 와 회색 알약 테두리 */
    fun choice(label: String, current: Boolean, onClick: () -> Unit): TextView =
        button(label, BtnStyle.SECONDARY, icon = if (current) Ic.CHECK else null) { if (!controller.dataSuspended) onClick() }.apply {
            if (current) background = pressable(rounded(C.NAV_ACTIVE, 16, C.SUB))
        }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** 다운로드 폴더(Download/Popolog)에 CSV 저장 */
    fun saveCsv(fileName: String, csv: String) = saveFile(fileName, csv.toByteArray(Charsets.UTF_8), "text/csv")

    /** 다운로드 폴더(Download/Popolog)에 저장 */
    fun saveFile(fileName: String, bytes: ByteArray, mime: String) {
        if (!requireAdmin()) return
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Popolog")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                backup.track(uri, fileName)
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                File(getExternalFilesDir(null), fileName).writeBytes(bytes)
            }
            toast("저장했어요: 다운로드/Popolog/$fileName")
        } catch (e: Exception) {
            val f = File(getExternalFilesDir(null), fileName)
            f.writeBytes(bytes)
            toast("저장했어요: ${f.absolutePath}")
        }
    }

    fun shareCsv(subject: String, csv: String) {
        if (!requireAdmin()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        try {
            startActivity(Intent.createChooser(send, subject))
        } catch (e: ActivityNotFoundException) {
            toast("공유할 앱이 없어요")
        }
    }

    companion object {
        private const val REQ_BACKUP_FOLDER = 43
        private const val REQ_CAR_PERMISSIONS = 42
        private const val KEY_OVERLAY_POS = "demo_overlay_pos"
        const val THEME_AUTO = "auto"
        const val THEME_DAY = "day"
        const val THEME_NIGHT = "night"

        /** Pleos 빌드에서 런타임 승인이 필요한 권한 (시뮬레이터 빌드는 선언하지 않아 건너뛴다) */
        private val CAR_PERMISSIONS = listOf(
            "pleos.car.permission.FUSED_LOCATION",
            "pleos.car.permission.CAR_MILEAGE",
            "pleos.car.permission.CAR_ENERGY",
            "pleos.car.permission.CAR_INFO",
        )
    }
}
