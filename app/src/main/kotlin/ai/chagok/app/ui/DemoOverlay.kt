package ai.chagok.app.ui

import ai.chagok.app.MainActivity
import ai.chagok.app.data.DemoSeed
import ai.chagok.app.model.PlaceKind
import ai.chagok.app.model.Powertrain
import ai.chagok.app.vehicle.SimulatedVehicle
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.hypot

/**
 * 시연 컨트롤. 실제 차량에는 없는 UI 라서 앱 본문에 섞지 않고, 창 위에 반투명하게 띄운다.
 * 버튼마다 아이콘 아래에 이름을 붙이고, 가로세로 두 배 크기로 그린다.
 * 접고 펴는 버튼은 패널과 따로 위에 둔다. 접으면 큰 원형 "시연" 버튼 하나다.
 * 시뮬레이터 빌드(가상 차량)에서만 나타나고, Pleos SDK 빌드에서는 그려지지 않는다.
 *
 * 본문과 달리 큰 화면 배율(uiScale)을 적용하지 않는다.
 */
class DemoOverlay(private val a: MainActivity) {
    private val c = a.controller
    private val density = a.resources.displayMetrics.density

    private fun px(v: Int) = (v * density).toInt()

    /** 그릴 것이 없으면 null (실제 차량 연결 시) */
    fun render(expanded: Boolean, onToggle: () -> Unit): View? {
        val sim = c.gateway as? SimulatedVehicle ?: return null
        return if (expanded) expandedView(sim, onToggle) else handle(onToggle)
    }

    /** 펼친 상태: 위에 "끌어서 이동" 안내와 접기 버튼(따로), 아래에 패널 */
    private fun expandedView(sim: SimulatedVehicle, onToggle: () -> Unit): View = android.widget.LinearLayout(a).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        addView(android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dragHint())
            addView(collapseButton(onToggle), android.widget.LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = px(16) })
        }, android.widget.LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.END
            bottomMargin = px(12)
        })
        // 기본 레이아웃(가로 MATCH)을 쓰면 접기 버튼 너비로 줄어들어 패널이 잘린다
        addView(bar(sim, onToggle), android.widget.LinearLayout.LayoutParams(WRAP, WRAP))
    }

    /** 패널과 따로 떨어진 접기 버튼 */
    private fun collapseButton(onToggle: () -> Unit): View = label("접기 ▾", 24f, C.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        minimumWidth = px(176)
        minimumHeight = px(88)
        background = a.pressable(a.rounded(PANEL_BG, 999, PANEL_LINE))
        alpha = 0.9f
        contentDescription = "시연 컨트롤 접기"
        setOnClickListener { onToggle() }
    }

    /** 접힌 상태: 강조색 알약 버튼 + 아래 "끌어서 이동" 표시. 눈에 잘 띄게 크고 색이 있다 */
    private fun handle(onToggle: () -> Unit): View = android.widget.LinearLayout(a).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(24), px(14), px(32), px(14))
            background = a.pressable(a.rounded(C.ACCENT, 999))
            elevation = px(8).toFloat()
            contentDescription = "시연 컨트롤 펼치기"
            addView(label("⠿", 32f, C.ON_ACCENT, bold = true))
            addView(label("시연", 30f, C.ON_ACCENT, bold = true).apply { setPadding(px(12), 0, 0, 0) })
            setOnClickListener { onToggle() }
        }, android.widget.LinearLayout.LayoutParams(WRAP, px(88)))
        addView(dragHint(), android.widget.LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(8) })
    }

    /** 끌어서 옮길 수 있다는 안내 */
    private fun dragHint(): View = label("⠿ 끌어서 이동", 18f, C.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(px(16), px(6), px(16), px(6))
        background = a.rounded(PANEL_BG, 999, PANEL_LINE)
    }

    /** 펼친 상태: 네 줄(좁은 창에서도 들어가게 줄마다 버튼 넷 이하). 운행·차량 / 기록·검증 / 음성·비용 / 연동·차종 */
    private fun bar(sim: SimulatedVehicle, onToggle: () -> Unit): View = a.vbox {
        background = a.rounded(PANEL_BG, 28, PANEL_LINE)
        setPadding(px(12), px(12), px(12), px(12))
        alpha = 0.9f
        addView(driveRow(sim, onToggle))
        addView(recordRow(sim), android.widget.LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(12) })
        addView(toolsRow(sim), android.widget.LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(12) })
        addView(miscRow(sim), android.widget.LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(12) })
    }

    /** 아랫줄: 음성(Gleo)·비용 입력·도착 질문·정비 시기·연동 내역 */
    private fun toolsRow(sim: SimulatedVehicle): View = a.hbox(Gravity.CENTER_VERTICAL) {
        fun put(v: View, gap: Int = 4) = addView(v, android.widget.LinearLayout.LayoutParams(px(BTN_W), px(BTN_H)).apply { marginStart = px(gap * 2) })
        val busy = sim.busy || !sim.parked
        addView(label("음성·비용", 22f, C.SUB, bold = true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), 0, px(8), 0) })
        // Gleo 가 앱 함수를 부르는 것과 같은 인텐트를 보낸다
        put(tool(Ic.MIC, "음성", "Gleo 음성 명령 시연", true) { gleoDialog() }, 2)
        put(tool(Ic.PLUS, "비용", "비용 입력 열기", !busy) { a.showExpenseInput() })
        // 내비가 경유지에 도착했다고 알린다 (정차 중이면 바로 묻고, 주행 중이면 세운 뒤에 묻는다)
        put(tool(Ic.PIN, "경유지", "경유지 도착 (내비 이벤트)", true) {
            (c.navi as? ai.chagok.app.nav.SimulatedNavi)?.arrive(
                ai.chagok.app.nav.Arrival(ai.chagok.app.nav.Arrival.Kind.WAYPOINT, "미사 휴게소", "경기 하남시 미사대로", c.lat, c.lng),
            )
            a.toast("경유지 도착을 알렸어요")
        })
        put(tool(Ic.WRENCH, "정비", "정비 시기 임박", !busy) { c.flows.demoMakeDue(); a.toast("엔진오일 교체 주기를 짧게 줄여 시기가 된 것으로 만들었어요") })
    }

    private fun gleoDialog() {
        val samples = listOf(
            "정비 12만 원, 스피드메이트" to ("ADD_EXPENSE" to """{"category":"maintenance","amount_won":120000,"place":"스피드메이트 판교점"}"""),
            "주차비 6천 원" to ("ADD_EXPENSE" to """{"category":"parking","amount_won":6000,"place":"판교역 공영주차장"}"""),
            "통행료 (금액은 못 들었을 때)" to ("ADD_EXPENSE" to """{"category":"toll"}"""),
            "이번 달 정비비 알려 줘" to ("QUERY_REPORT" to """{"category":"maintenance","period":"this_month","action":"show"}"""),
            "올해 운행기록부 PDF 내보내기" to ("QUERY_REPORT" to """{"period":"this_year","action":"export_pdf"}"""),
            "이번 달 비용 CSV 내보내기" to ("QUERY_REPORT" to """{"period":"this_month","action":"export_csv"}"""),
        )
        var dlg: android.app.Dialog? = null
        val body = a.vbox {
            setPadding(a.dp(24), a.dp(8), a.dp(24), a.dp(8))
            add(a.text("Gleo 가 앱 함수를 부를 때와 같은 호출을 보냅니다. 차량에서는 말하면 Gleo 가 이 값을 채웁니다.", 15f, C.SUB))
            for ((say, call) in samples) {
                add(a.choice("“$say”", false) {
                    dlg?.dismiss()
                    a.simulateGleo(call.first, call.second)
                }, top = 10)
            }
        }
        dlg = a.dialog("Gleo 음성 명령 시연", body, null) {}
    }

    /** 운행 줄: 도착지 드롭다운은 폭이 넓어 따로 한 줄에 두고, 그 아래에 주유·도중 정차·시동 버튼을 둔다 */
    private fun driveRow(sim: SimulatedVehicle, onToggle: () -> Unit): View = android.widget.LinearLayout(a).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        val busy = sim.busy || !sim.parked
        val ev = sim.info.powertrain == Powertrain.EV
        addView(destinations(sim, busy, onToggle), android.widget.LinearLayout.LayoutParams(WRAP, px(BTN_H)))
        addView(a.hbox(Gravity.CENTER_VERTICAL) {
            fun put(v: View, gap: Int = 4) = addView(v, android.widget.LinearLayout.LayoutParams(px(BTN_W), px(BTN_H)).apply { marginStart = px(gap * 2) })
            addView(label("운행·차량", 22f, C.SUB, bold = true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), 0, px(8), 0) })
            put(tool(if (ev) Ic.PLUG else Ic.FUEL, if (ev) "충전" else "주유", if (ev) "충전하기 (→80%)" else "주유하기 (가득)", !busy) {
                sim.refill(if (ev) 80.0 else 100.0) { c.flushEnergy() }
            }, 2)
            // 주행 중에만: 신호 대기·정체로 6분 서 있기 → 정차 5분이 넘으면 운행이 끊기고 다시 출발하면 새 운행
            put(tool(Ic.PARKING, "도중정차", "도중 정차 (6분)", sim.driving) { sim.stopFor(6) })
            // 시동 끄고 다시 켜기: 주행 중이면 그 자리에서 운행 종료, 켜면 운전자를 다시 판별
            put(tool(Ic.CAR, "시동", "시동 끄고 다시 켜기", !sim.refilling) { c.demoRestartEngine() })
        }, android.widget.LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(12) })
    }

    /** 마지막 줄: 외부 연동 가져오기, 차종 전환 */
    private fun miscRow(sim: SimulatedVehicle): View = a.hbox(Gravity.CENTER_VERTICAL) {
        fun put(v: View, gap: Int = 4) = addView(v, android.widget.LinearLayout.LayoutParams(px(BTN_W), px(BTN_H)).apply { marginStart = px(gap * 2) })
        val busy = sim.busy || !sim.parked
        val ev = sim.info.powertrain == Powertrain.EV
        addView(label("연동·차종", 22f, C.SUB, bold = true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), 0, px(8), 0) })
        put(tool(Ic.RECEIPT, "연동", "연동 내역 가져오기", true) {
            val r = c.flows.sync(c.expenseSource)
            a.toast("연동 내역 ${r.added}건을 가져왔어요" + if (r.duplicates > 0) " · 이미 받은 ${r.duplicates}건 제외" else "")
            a.select(MainActivity.Tab.LEDGER)
        }, 2)
        // 차종을 바꾸면 주유 대신 충전 감지(배터리 SoC 상승)를 보여 준다
        put(tool(Ic.SWAP, "차종", if (ev) "가솔린 차로 전환" else "전기차로 전환", !busy) {
            c.switchPowertrain(if (ev) Powertrain.ICE else Powertrain.EV)
        })
    }

    /** 기록·검증 줄: 위변조 시연, 하루 마감, 앱 끈 채 운행, 초기화 */
    private fun recordRow(sim: SimulatedVehicle): View = a.hbox(Gravity.CENTER_VERTICAL) {
        fun put(v: View, gap: Int = 4) = addView(v, android.widget.LinearLayout.LayoutParams(px(BTN_W), px(BTN_H)).apply { marginStart = px(gap * 2) })
        val busy = sim.busy || !sim.parked
        addView(label("기록·검증", 22f, C.SUB, bold = true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), 0, px(8), 0) })
        // 위변조 검증 시연: DB 의 지난 운행 하나를 몰래 고쳤다가 되돌린다. 결과는 세무 리포트의 '기록 무결성' 카드에서 본다
        val forged = c.forgedSeq != null
        put(tool(if (forged) Ic.CHECK else Ic.ALERT, if (forged) "되돌리기" else "위조", if (forged) "위조 되돌리기" else "기록 위조해 보기", !busy) {
            if (!a.requireAdmin()) return@tool
            if (forged) c.undoForge() else c.forgeRecord()
            a.select(MainActivity.Tab.TAX)
        })
        // 하루 마감: 오늘 마지막 해시를 앵커로 묶는다 (타임스탬프 기관은 시뮬레이션)
        put(tool(Ic.SHIELD, "마감", "하루 마감 (앵커)", !busy) { anchorToday(c, a) })
        // 앱을 끈 채 달린 거리: 계기판만 12.4km 늘어난 채로 다시 시동해 보정 운행이 생기는 것을 보여 준다
        put(tool(Ic.SWAP, "앱끈운행", "앱 끈 채 운행 (12km)", !busy) { c.demoOfflineDrive(12.4) })
        put(tool(Ic.RESET, "초기화", "시연 데이터 초기화", !busy) {
            if (!a.requireAdmin()) return@tool
            a.dialog("시연 데이터를 초기화할까요?", a.text("  지금까지의 기록을 지우고 6월 출고 이후 예시 기록을 다시 만듭니다.", 16f, C.SUB), "초기화") {
                c.resetDemo()
            }
        })
    }

    /**
     * 도착지 드롭다운. 고르면 그곳으로 운행이 시작되고, 주행 화면이 보이도록 오버레이는 접힌다.
     * 아래쪽 "OOO 탑승"은 다른 사람이 타서 운전석을 자기 위치로 맞추는 것을 흉내 낸다 (운전자 판별 시연).
     */
    private fun destinations(sim: SimulatedVehicle, busy: Boolean, onToggle: () -> Unit): View {
        data class Dest(val label: String, val run: () -> Unit)
        fun go(label: String, lat: Double, lng: Double) = Dest(label) { sim.driveTo(lat, lng) }
        val dests = buildList {
            c.places.firstOrNull { it.kind == PlaceKind.OFFICE }?.let { add(go("→ 회사", it.lat, it.lng)) }
            c.places.firstOrNull { it.kind == PlaceKind.CLIENT }?.let { add(go("→ ${it.name.substringBefore(" (")}", it.lat, it.lng)) }
            c.places.firstOrNull { it.kind == PlaceKind.HOME }?.let { add(go("→ 집", it.lat, it.lng)) }
            add(go("→ ${DemoSeed.MALL_NAME}", DemoSeed.MALL_LAT, DemoSeed.MALL_LNG))
            add(go("→ ${DemoSeed.REST_NAME}", DemoSeed.REST_LAT, DemoSeed.REST_LNG))
            c.drivers.filter { it.id != c.driver?.driver?.id && it.seatPosition != null }.forEach { d ->
                add(Dest("${d.name} 탑승") { sim.seatDriver(d.seatPosition!!) })
            }
        }
        // 0번은 안내 문구 자리. 고른 뒤에는 다시 0번으로 돌린다
        val labels = listOf(if (busy) "시연 · 이동 중…" else "시연 · 도착지·탑승 ▾") + dests.map { it.label }
        val adapter = object : ArrayAdapter<String>(a, 0, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                label(getItem(position).orEmpty(), 26f, if (busy) C.DIM else C.TEXT, bold = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(24), 0, px(24), 0)
                    minHeight = px(BTN_H)
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                label(if (position == 0) "도착지 또는 탑승자 고르기" else getItem(position).orEmpty(), 30f, if (position == 0) C.DIM else C.TEXT, bold = position > 0).apply {
                    setPadding(px(32), px(24), px(32), px(24))
                }
        }
        return Spinner(a, Spinner.MODE_DROPDOWN).apply {
            this.adapter = adapter
            background = a.pressable(a.rounded(C.SURFACE2, 20))
            setPopupBackgroundDrawable(a.rounded(C.SURFACE2, 24, C.LINE))
            isEnabled = !busy
            contentDescription = "시연 도착지"
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) return
                    val d = dests[position - 1]
                    setSelection(0)
                    d.run()
                    onToggle()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    /** 아이콘 아래에 이름을 붙인 버튼. [desc] 는 접근성 설명(자세한 뜻) */
    private fun tool(ic: Ic, name: String, desc: String, enabled: Boolean, onClick: () -> Unit): View =
        android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(android.widget.ImageView(a).apply {
                setImageDrawable(a.iconDrawable(ic, C.TEXT, 40))
                scaleType = android.widget.ImageView.ScaleType.CENTER
            }, android.widget.LinearLayout.LayoutParams(px(40), px(40)))
            addView(label(name, 17f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, px(2), 0, 0) })
            background = a.pressable(a.rounded(C.SURFACE2, 20))
            contentDescription = desc
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f
            setOnClickListener { onClick() }
        }

    /** 큰 화면 배율을 거치지 않는 글자 */
    private fun label(s: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(a).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1
        includeFontPadding = false
    }

    companion object {
        /** 중립 회색, 약 90% 불투명 (크게 키운 만큼 뒤 글자가 비치면 읽기 어려워서 거의 불투명하게 둔다. 보라는 쓰지 않는다) */
        private val PANEL_BG get() = (C.SURFACE2 and 0x00FFFFFF) or (230 shl 24)
        private val PANEL_LINE get() = (C.SUB and 0x00FFFFFF) or (140 shl 24)

        /** 버튼 크기 (dp). 예전 44dp 정사각형의 두 배 */
        private const val BTN_W = 96
        private const val BTN_H = 88
    }
}

/**
 * 끌어서 옮길 수 있는 틀. 손가락이 터치 슬롭보다 덜 움직이면 안의 버튼·드롭다운이 탭을 받고,
 * 그보다 많이 움직이면 이 틀이 이벤트를 가로채 위치를 옮긴다.
 */
class DragFrame(
    context: Context,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDragEnd: () -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                dragging = true
                lastX = e.rawX; lastY = e.rawY
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) dragging = true
                if (dragging) {
                    onDrag(e.rawX - lastX, e.rawY - lastY)
                    lastX = e.rawX; lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd()
                dragging = false
            }
        }
        // 버튼 사이 빈 곳을 잡아도 끌 수 있게 항상 받는다
        return true
    }
}
