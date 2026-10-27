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
    private fun handle(onToggle: () -> Unit): View = label("시연", 12f, C.VIOLET, bold = true).apply {
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
        fun put(v: View, w: Int, gap: Int) = addView(v, android.widget.LinearLayout.LayoutParams(w, px(36)).apply { marginStart = px(gap) })

        val busy = sim.busy || !sim.parked
        val ev = sim.info.powertrain == Powertrain.EV
        put(destinations(sim, busy, onToggle), WRAP, 0)
        put(icon(if (ev) "🔌" else "⛽", if (ev) "충전하기 (→80%)" else "주유하기 (가득)", !busy) {
            sim.refill(if (ev) 80.0 else 100.0) { c.flushEnergy() }
        }, px(40), 6)
        // 차종을 바꾸면 주유 대신 충전 감지(배터리 SoC 상승)를 보여 준다
        put(icon("⇄", if (ev) "가솔린 차로 전환" else "전기차로 전환", !busy) {
            c.switchPowertrain(if (ev) Powertrain.ICE else Powertrain.EV)
        }, px(40), 4)
        put(icon("↺", "시연 데이터 초기화", !busy) {
            a.dialog("시연 데이터를 초기화할까요?", a.text("  지금까지의 기록을 지우고 6월 출고 이후 예시 기록을 다시 만듭니다.", 16f, C.SUB), "초기화") {
                c.resetDemo()
            }
        }, px(40), 4)
        put(icon("▾", "시연 컨트롤 접기", true) { onToggle() }, px(32), 2)
    }

    /**
     * 도착지 드롭다운. 고르면 그곳으로 운행이 시작되고, 주행 화면이 보이도록 오버레이는 접힌다.
     * 아래쪽 "👤 OOO 탑승"은 다른 사람이 타서 운전석을 자기 위치로 맞추는 것을 흉내 낸다 (운전자 판별 시연).
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
                add(Dest("👤 ${d.name} 탑승") { sim.seatDriver(d.seatPosition!!) })
            }
        }
        // 0번은 안내 문구 자리. 고른 뒤에는 다시 0번으로 돌린다
        val labels = listOf(if (busy) "시연 · 이동 중…" else "시연 · 도착지·탑승 ▾") + dests.map { it.label }
        val adapter = object : ArrayAdapter<String>(a, 0, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                label(getItem(position).orEmpty(), 14f, if (busy) C.DIM else C.TEXT, bold = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(12), 0, px(12), 0)
                    minHeight = px(36)
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

    private fun icon(glyph: String, desc: String, enabled: Boolean, onClick: () -> Unit): TextView =
        label(glyph, 17f, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
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
        /** 보라빛 어두운 유리 느낌, 약 40% 불투명 */
        private val PANEL_BG = Color.argb(104, 0x2A, 0x23, 0x42)
        private val PANEL_LINE = Color.argb(140, 0xC4, 0xB5, 0xFD)
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
