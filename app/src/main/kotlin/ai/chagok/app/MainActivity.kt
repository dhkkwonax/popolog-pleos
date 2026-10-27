package ai.chagok.app

import ai.chagok.app.core.KoreanAmountParser
import ai.chagok.app.model.ClassifiedBy
import ai.chagok.app.model.Expense
import ai.chagok.app.model.ExpenseSource
import ai.chagok.app.model.Powertrain
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import ai.chagok.app.ui.BtnStyle
import ai.chagok.app.ui.C
import ai.chagok.app.ui.DemoOverlay
import ai.chagok.app.ui.DragFrame
import ai.chagok.app.ui.HomeScreen
import ai.chagok.app.ui.LedgerScreen
import ai.chagok.app.ui.LogbookScreen
import ai.chagok.app.ui.MATCH
import ai.chagok.app.ui.PlacesScreen
import ai.chagok.app.ui.SettingsScreen
import ai.chagok.app.ui.TaxScreen
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
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    lateinit var controller: AppController
        private set

    enum class Tab(val label: String, val icon: String) {
        HOME("홈", "⌂"),
        LEDGER("장부", "₩"),
        LOGBOOK("운행일지", "⇄"),
        TAX("세무 리포트", "%"),
        PLACES("장소", "⌖"),
        SETTINGS("설정", "⚙"),
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
    private var voiceCallback: ((String) -> Unit)? = null

    private val onChanged: () -> Unit = {
        updateStatus()
        val key = dataKey()
        if (key != renderedKey) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        window.setBackgroundDrawable(ColorDrawable(C.BG))
        controller = AppController(this)
        buildShell()
        controller.addListener(onChanged)
        requestCarPermissions()
        controller.start()
        render()
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
        buildShell()
        render()
    }

    override fun onDestroy() {
        controller.removeListener(onChanged)
        controller.stop()
        super.onDestroy()
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
                controller.db.setSetting(KEY_OVERLAY_POS, "${p.first},${p.second}")
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
        val v = DemoOverlay(this).render(demoExpanded) {
            demoExpanded = !demoExpanded
            renderOverlay()
        } ?: return
        overlayHost.addView(v, FrameLayout.LayoutParams(WRAP, WRAP))
        // 본문이 다 놓인 뒤에야 빈 곳을 잴 수 있다
        stage.post { placeOverlay() }
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
        val w = overlayHost.width.takeIf { it > 0 } ?: overlayHost.measuredWidth
        val h = overlayHost.height.takeIf { it > 0 } ?: overlayHost.measuredHeight
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
        add(text("차곡", 22f, bold = true), WRAP, WRAP)
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
            val item = vbox(Gravity.CENTER) {
                setPadding(dp(6), dp(if (wide) 14 else 8), dp(6), dp(if (wide) 14 else 8))
                background = pressable(rounded(if (selected) C.ACCENT_BG else Color.TRANSPARENT, 16))
                add(text(t.icon, if (wide) 26f else 20f, if (selected) C.ACCENT else C.SUB, bold = true).apply { gravity = Gravity.CENTER }, MATCH, WRAP)
                add(text(t.label, if (wide) 15f else 12f, if (selected) C.ACCENT else C.SUB, bold = selected).apply { gravity = Gravity.CENTER }, MATCH, WRAP, top = 4)
                setOnClickListener { select(t) }
            }
            rail.add(item, 0, WRAP, 1f)
        }
    }

    /** 2칸(가로) 창의 왼쪽 메뉴 한 줄: 아이콘 오른쪽에 메뉴이름을 본문 제목 크기로. 선택된 줄은 전체 배경으로 강조 */
    private fun railRow(t: Tab, selected: Boolean): View = hbox(Gravity.CENTER_VERTICAL) {
        setPadding(dp(16), dp(16), dp(24), dp(16))
        background = pressable(rounded(if (selected) C.ACCENT_BG else Color.TRANSPARENT, 16))
        val color = if (selected) C.ACCENT else C.SUB
        add(text(t.icon, 26f, color, bold = true).apply { gravity = Gravity.CENTER }, dp(36), WRAP, end = 14)
        add(text(t.label, 20f, if (selected) C.ACCENT else C.TEXT, bold = selected, lines = 1), WRAP, WRAP)
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
            tab, trips.size, trips.firstOrNull()?.let { it.id to it.purpose }, expenses.size,
            expenses.count { it.pendingAmount }, expenses.sumOf { it.amountWon }, places.size,
            askTripId, askExpenseId, monthOffset, powertrain, lastNotice,
            driver?.driver?.id, driver?.method, driverGreeting, drivers,
            (gateway as? ai.chagok.app.vehicle.SimulatedVehicle)?.busy,
        ).joinToString("|")
    }

    fun render() {
        renderedKey = dataKey()
        renderRail()
        updateStatus()
        renderOverlay()
        content.removeAllViews()
        val view = when (tab) {
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
        val title = vbox {
            add(text(tab.label, 28f, bold = true), WRAP, WRAP)
            add(text(c.gateway.info.title, 15f, C.SUB), WRAP, WRAP, top = 4)
        }
        statusBar.add(title, 0, WRAP, 1f)
        val ev = c.gateway.info.powertrain == Powertrain.EV
        val chips = hbox(Gravity.END or Gravity.CENTER_VERTICAL) {
            // 지금 운전석에 앉은 사람. 눌러서 바꿀 수 있다
            c.driver?.let { d ->
                add(chip("👤 ${d.driver.name} · ${d.method.label}", C.BLUE, C.BLUE_BG, 15f).apply {
                    contentDescription = "운전자 바꾸기"
                    setOnClickListener { pickDriver() }
                }, WRAP, WRAP, end = 8)
            }
            if (c.isDriving) {
                add(chip("● 주행 중 " + AppController.fmtKm(c.liveTripKm), C.ACCENT, C.ACCENT_BG, 15f), WRAP, WRAP, end = 8)
            } else {
                add(chip("P 주차", C.SUB, C.SURFACE2, 15f), WRAP, WRAP, end = 8)
            }
            add(chip("계기판 " + AppController.fmtKm(c.odoKm), C.TEXT, C.SURFACE2, 15f), WRAP, WRAP, end = 8)
            val low = c.energyPercent < 20
            add(
                chip((if (ev) "배터리 " else "연료 ") + Math.round(c.energyPercent) + "%", if (low) C.AMBER else C.TEXT, if (low) C.AMBER_BG else C.SURFACE2, 15f),
                WRAP, WRAP, end = 8,
            )
        }
        // 좁은 창에서는 칩이 차종·번호판을 밀어 줄바꿈시키므로 제목 아래 줄로 내린다
        if (isNarrow) title.add(chips, WRAP, WRAP, top = 10) else statusBar.add(chips, WRAP, WRAP)
    }

    /** 운전자 고르기. 시동 때 자동으로 정해지지만 틀렸으면 여기서 바꾼다 */
    fun pickDriver() {
        val list = vbox().apply { setPadding(dp(24), 0, dp(24), dp(8)) }
        lateinit var dlg: AlertDialog
        for (d in controller.drivers) {
            val current = controller.driver?.driver?.id == d.id
            list.add(button("${d.name} · ${d.department}", if (current) BtnStyle.PRIMARY else BtnStyle.SECONDARY) {
                controller.chooseDriver(d.id)
                dlg.dismiss()
            }, top = 10)
        }
        dlg = dialog("지금 운전하는 분", list, null) {}
    }

    // ---- 음성 입력 ----

    /**
     * 차량에서는 Pleos SpeechToText SDK로, 일반 기기에서는 안드로이드 음성 인식으로 받는다.
     * 음성 인식이 없는 기기(에뮬레이터 등)는 문장을 직접 입력하는 창으로 대신한다.
     */
    fun listen(prompt: String, hint: String, onText: (String) -> Unit) {
        controller.speak(prompt)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        voiceCallback = onText
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            voiceCallback = null
            typedVoice(prompt, hint, onText)
        } catch (e: SecurityException) {
            voiceCallback = null
            typedVoice(prompt, hint, onText)
        }
    }

    private fun typedVoice(prompt: String, hint: String, onText: (String) -> Unit) {
        val input = editText(hint, InputType.TYPE_CLASS_TEXT)
        val body = vbox {
            setPadding(dp(24), dp(8), dp(24), dp(0))
            add(text("이 기기에 음성 인식이 없어 문장으로 입력합니다. 차량에서는 Pleos STT로 바로 듣습니다.", 15f, C.SUB))
            add(input, top = 16)
        }
        dialog("🎤 $prompt", body, "확인") { onText(input.text.toString()) }
    }

    @Deprecated("Activity result API는 AndroidX가 필요해 프레임워크 API를 쓴다")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE) return
        val cb = voiceCallback ?: return
        voiceCallback = null
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && text != null) cb(text)
    }

    /** 주유·충전 금액을 음성으로 받는다 */
    fun askAmountByVoice(e: Expense) {
        listen("${e.category.label} 금액을 말씀해 주세요", "예: 칠만 삼천 원") { said ->
            val amount = KoreanAmountParser.parse(said)
            if (amount == null) {
                toast("금액을 알아듣지 못했어요: \"$said\"")
            } else {
                controller.setExpenseAmount(e.id, amount, ExpenseSource.VOICE)
            }
        }
    }

    fun askAmountByKeypad(e: Expense) {
        val input = editText("금액 (원)", InputType.TYPE_CLASS_NUMBER)
        val body = vbox {
            setPadding(dp(24), dp(8), dp(24), 0)
            e.quantity?.let {
                add(text("감지량 약 ${AppController.fmt1(it)}${if (e.category.name == "CHARGE") "kWh" else "L"} · ${e.memo}", 15f, C.SUB))
            }
            add(input, top = 12)
        }
        dialog("${e.category.emoji} ${e.category.label} 금액 입력", body, "저장") {
            val v = input.text.toString().toLongOrNull()
            if (v != null && v > 0) controller.setExpenseAmount(e.id, v, ExpenseSource.MANUAL)
        }
    }

    /** 미분류 운행의 목적을 음성으로 묻는다 */
    fun askPurposeByVoice(t: Trip) {
        listen("업무 운행이었나요?", "예: 거래처 미팅 / 개인 용무 / 출근") { said ->
            val p = when {
                listOf("출근", "퇴근", "출퇴근").any { it in said } -> TripPurpose.COMMUTE
                listOf("개인", "비업무", "아니", "사적").any { it in said } -> TripPurpose.PERSONAL
                listOf("업무", "미팅", "거래처", "출장", "납품", "응", "네", "맞아").any { it in said } -> TripPurpose.BUSINESS
                else -> null
            }
            if (p == null) toast("목적을 알아듣지 못했어요: \"$said\"")
            else controller.classifyTrip(t.id, p, ClassifiedBy.VOICE)
        }
    }

    fun choosePurpose(t: Trip) {
        val options = TripPurpose.values().filter { it != TripPurpose.UNCLASSIFIED }
        var dlg: AlertDialog? = null
        val body = vbox {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            add(text("${t.fromName.ifEmpty { "출발지" }} → ${t.toName.ifEmpty { "미등록 장소" }} · ${AppController.fmtKm(t.distanceKm)}", 16f, C.SUB))
            for (p in options) {
                add(button(p.label, if (p == t.purpose) BtnStyle.PRIMARY else BtnStyle.SECONDARY) {
                    controller.classifyTrip(t.id, p, ClassifiedBy.MANUAL)
                    dlg?.dismiss()
                }, top = 10)
            }
        }
        dlg = dialog("운행 목적", body, null) {}
    }

    // ---- 공용 다이얼로그 ----

    fun editText(hint: String, type: Int, value: String = ""): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
        setText(value)
        textSize = sp(20f)
        setTextColor(C.TEXT)
        setHintTextColor(C.DIM)
        background = rounded(C.SURFACE2, 12)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    fun dialog(title: String, body: View, positive: String?, onOk: () -> Unit): AlertDialog {
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(body)
            .setNegativeButton("닫기", null)
        if (positive != null) b.setPositiveButton(positive) { _, _ -> onOk() }
        return b.show()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** 다운로드 폴더(Download/Chagok)에 CSV 저장 */
    fun saveCsv(fileName: String, csv: String) {
        try {
            val bytes = csv.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Chagok")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                File(getExternalFilesDir(null), fileName).writeBytes(bytes)
            }
            toast("저장했어요: 다운로드/Chagok/$fileName")
        } catch (e: Exception) {
            val f = File(getExternalFilesDir(null), fileName)
            f.writeBytes(csv.toByteArray(Charsets.UTF_8))
            toast("저장했어요: ${f.absolutePath}")
        }
    }

    fun shareCsv(subject: String, csv: String) {
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
        private const val REQ_VOICE = 41
        private const val REQ_CAR_PERMISSIONS = 42
        private const val KEY_OVERLAY_POS = "demo_overlay_pos"

        /** Pleos 빌드에서 런타임 승인이 필요한 권한 (시뮬레이터 빌드는 선언하지 않아 건너뛴다) */
        private val CAR_PERMISSIONS = listOf(
            "pleos.car.permission.FUSED_LOCATION",
            "pleos.car.permission.CAR_MILEAGE",
            "pleos.car.permission.CAR_ENERGY",
            "pleos.car.permission.CAR_INFO",
        )
    }
}
