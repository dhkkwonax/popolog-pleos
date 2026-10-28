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
 * 시연 컨트롤. 실제 차량에는 없는 UI 라서 앱 본문에 섞지 않고, 창 위에 작고 반투명하게 띄운다.
 * 펼쳐도 한 줄(도착지 드롭다운 + 아이콘 버튼)이고, 접으면 작은 원형 버튼 하나다.
 * 시뮬레이터 빌드(가상 차량)에서만 나타나고, Pleos SDK 빌드에서는 그려지지 않는다.
 *
 * 본문과 달리 큰 화면 배율(uiScale)을 적용하지 않는다. 시연자만 쓰는 UI 라 최대한 작게 둔다.
 */
class DemoOverlay(private val a: MainActivity) {
    private val c = a.controller
    private val density = a.resources.displayMetrics.density

    private fun px(v: Int) = (v * density).toInt()

    /** 그릴 것이 없으면 null (실제 차량 연결 시) */
    fun render(expanded: Boolean, onToggle: () -> Unit): View? {
        val sim = c.gateway as? SimulatedVehicle ?: return null
        return if (expanded) bar(sim, onToggle) else handle(onToggle)
    }

    /** 접힌 상태: 작은 반투명 원형 버튼 */
    private fun handle(onToggle: () -> Unit): View = label("시연", 12f, C.SUB, bold = true).apply {
        gravity = Gravity.CENTER
        minimumWidth = px(44)
        minimumHeight = px(44)
        background = a.pressable(a.rounded(PANEL_BG, 999, PANEL_LINE))
        alpha = 0.75f
        contentDescription = "시연 컨트롤 펼치기"
        setOnClickListener { onToggle() }
    }

    /** 펼친 상태: 한 줄 */
    private fun bar(sim: SimulatedVehicle, onToggle: () -> Unit): View = a.hbox(Gravity.CENTER_VERTICAL) {
        background = a.rounded(PANEL_BG, 14, PANEL_LINE)
        setPadding(px(6), px(6), px(6), px(6))
        alpha = 0.9f
        // 본문 add() 는 큰 화면 배율이 걸린 여백을 쓰므로, 여기서는 배율 없는 간격으로 붙인다
        // 시연자도 손가락으로 누르므로 터치 영역은 44dp
        fun put(v: View, w: Int, gap: Int) = addView(v, android.widget.LinearLayout.LayoutParams(w, px(TOUCH)).apply { marginStart = px(gap) })

        val busy = sim.busy || !sim.parked
        val ev = sim.info.powertrain == Powertrain.EV
        put(destinations(sim, busy, onToggle), WRAP, 0)
        put(tool(if (ev) Ic.PLUG else Ic.FUEL, if (ev) "충전하기 (→80%)" else "주유하기 (가득)", !busy) {
            sim.refill(if (ev) 80.0 else 100.0) { c.flushEnergy() }
        }, px(TOUCH), 6)
        // 주행 중에만: 신호 대기·정체로 6분 서 있기 → 정차 5분이 넘으면 운행이 끊기고 다시 출발하면 새 운행
        put(tool(Ic.PARKING, "도중 정차 (6분)", sim.driving) { sim.stopFor(6) }, px(TOUCH), 4)
        // 시동 끄고 다시 켜기: 주행 중이면 그 자리에서 운행 종료, 켜면 운전자를 다시 판별
        put(tool(Ic.CAR, "시동 끄고 다시 켜기", !sim.refilling) { c.demoRestartEngine() }, px(TOUCH), 4)
        // 차종을 바꾸면 주유 대신 충전 감지(배터리 SoC 상승)를 보여 준다
        put(tool(Ic.SWAP, if (ev) "가솔린 차로 전환" else "전기차로 전환", !busy) {
            c.switchPowertrain(if (ev) Powertrain.ICE else Powertrain.EV)
        }, px(TOUCH), 4)
        // 위변조 검증 시연: DB 의 지난 운행 하나를 몰래 고쳤다가 되돌린다. 결과는 세무 리포트의 '기록 무결성' 카드에서 본다
        val forged = c.forgedSeq != null
        put(tool(if (forged) Ic.CHECK else Ic.ALERT, if (forged) "위조 되돌리기" else "기록 위조해 보기", !busy) {
            if (forged) c.undoForge() else c.forgeRecord()
            a.select(MainActivity.Tab.TAX)
        }, px(TOUCH), 4)
        // 하루 마감: 오늘 마지막 해시를 앵커로 묶는다 (타임스탬프 기관은 시뮬레이션)
        put(tool(Ic.SHIELD, "하루 마감 (앵커)", !busy) { anchorToday(c, a) }, px(TOUCH), 4)
        // 앱을 끈 채 달린 거리: 계기판만 12.4km 늘어난 채로 다시 시동해 보정 운행이 생기는 것을 보여 준다
        put(tool(Ic.SWAP, "앱 끈 채 운행 (12km)", !busy) { c.demoOfflineDrive(12.4) }, px(TOUCH), 4)
        put(tool(Ic.RESET, "시연 데이터 초기화", !busy) {
            a.dialog("시연 데이터를 초기화할까요?", a.text("  지금까지의 기록을 지우고 6월 출고 이후 예시 기록을 다시 만듭니다.", 16f, C.SUB), "초기화") {
                c.resetDemo()
            }
        }, px(TOUCH), 4)
        put(tool(Ic.CHEVRON_DOWN, "시연 컨트롤 접기", true) { onToggle() }, px(TOUCH), 2)
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
            c.drivers.filter { it.id != c.driver?.driver?.id && it.seatPosition != null }.forEach { d ->
                add(Dest("${d.name} 탑승") { sim.seatDriver(d.seatPosition!!) })
            }
        }
        // 0번은 안내 문구 자리. 고른 뒤에는 다시 0번으로 돌린다
        val labels = listOf(if (busy) "시연 · 이동 중…" else "시연 · 도착지·탑승 ▾") + dests.map { it.label }
        val adapter = object : ArrayAdapter<String>(a, 0, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                label(getItem(position).orEmpty(), 14f, if (busy) C.DIM else C.TEXT, bold = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(12), 0, px(12), 0)
                    minHeight = px(TOUCH)
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                label(if (position == 0) "도착지 또는 탑승자 고르기" else getItem(position).orEmpty(), 15f, if (position == 0) C.DIM else C.TEXT, bold = position > 0).apply {
                    setPadding(px(16), px(12), px(16), px(12))
                }
        }
        return Spinner(a, Spinner.MODE_DROPDOWN).apply {
            this.adapter = adapter
            background = a.pressable(a.rounded(C.SURFACE2, 10))
            setPopupBackgroundDrawable(a.rounded(C.SURFACE2, 12, C.LINE))
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

    private fun tool(ic: Ic, desc: String, enabled: Boolean, onClick: () -> Unit): View =
        android.widget.ImageView(a).apply {
            setImageDrawable(a.iconDrawable(ic, C.TEXT, 20))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = a.pressable(a.rounded(C.SURFACE2, 10))
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
        /** 중립 회색, 약 40% 불투명 (보라는 쓰지 않는다) */
        private val PANEL_BG get() = (C.SURFACE2 and 0x00FFFFFF) or (104 shl 24)
        private val PANEL_LINE get() = (C.SUB and 0x00FFFFFF) or (140 shl 24)

        /** 터치 영역 (dp) */
        private const val TOUCH = 44
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
