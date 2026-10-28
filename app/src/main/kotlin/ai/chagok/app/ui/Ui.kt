package ai.chagok.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 색 토큰. Pleos 시스템 UI 와 같이 Android 시스템 야간 모드(Configuration.uiMode)를 따라 주간·야간 세트를 고른다.
 * 바탕은 Pleos 의 흰/검정·그래파이트에 맞춘 무채색이고, 강조색은 내비 현재위치 파랑에서 채도를 낮춘 차분한 파랑이다.
 * 강조색은 주요 버튼 하나와 데이터 막대에만, 노랑은 '조치 필요'에만 쓴다. 글자는 모든 표면에서 4.5:1 이상.
 */
object C {
    var night = true
        private set
    var BG = 0; private set
    var RAIL = 0; private set
    var SURFACE = 0; private set
    var SURFACE2 = 0; private set
    var LINE = 0; private set
    var TEXT = 0; private set
    var SUB = 0; private set
    var DIM = 0; private set

    /** 선택된 메뉴·탭: 강조색이 아닌 중립 회색 알약 */
    var NAV_ACTIVE = 0; private set
    var ACCENT = 0; private set
    var ACCENT_BG = 0; private set

    /** 강조색으로 채운 주요 버튼 위 글자 */
    var ON_ACCENT = 0; private set

    /** 정상·완료 표시 (체크) */
    var POSITIVE = 0; private set
    var AMBER = 0; private set
    var AMBER_BG = 0; private set
    var ON_AMBER = 0; private set
    var RED = 0; private set
    var RED_BG = 0; private set

    /** 누를 때 물결 */
    var RIPPLE = 0; private set

    init { use(night = true) }

    fun use(night: Boolean) {
        this.night = night
        fun c(h: String) = Color.parseColor(h)
        if (night) {
            BG = c("#0B0C0E"); RAIL = c("#101114"); SURFACE = c("#17191C"); SURFACE2 = c("#222428"); LINE = c("#2C2F34")
            TEXT = c("#F2F3F5"); SUB = c("#B9BEC6"); DIM = c("#979DA6"); NAV_ACTIVE = c("#2C2F34")
            ACCENT = c("#86AAF2"); ACCENT_BG = c("#1A2740"); ON_ACCENT = c("#0B1A33")
            POSITIVE = c("#5BD08A"); AMBER = c("#F2B33D"); AMBER_BG = c("#33270E"); ON_AMBER = c("#2A1E00")
            RED = c("#F87171"); RED_BG = c("#3B1A1A"); RIPPLE = Color.argb(60, 255, 255, 255)
        } else {
            BG = c("#F3F4F6"); RAIL = c("#FFFFFF"); SURFACE = c("#FFFFFF"); SURFACE2 = c("#ECEEF1"); LINE = c("#DDE0E5")
            TEXT = c("#15171A"); SUB = c("#454B54"); DIM = c("#5B626C"); NAV_ACTIVE = c("#E2E5EA")
            ACCENT = c("#2F5FC4"); ACCENT_BG = c("#E6EDFA"); ON_ACCENT = c("#FFFFFF")
            POSITIVE = c("#1D7A43"); AMBER = c("#8A4B00"); AMBER_BG = c("#FFF3D6"); ON_AMBER = c("#FFFFFF")
            RED = c("#B42318"); RED_BG = c("#FDECEA"); RIPPLE = Color.argb(40, 0, 0, 0)
        }
    }

    /** 시스템 야간 모드인지 */
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/** 외곽선 아이콘 한 세트 (res/drawable/ic_*.xml, 24 그리드·1.75 선). 이모지 대신 쓴다 */
enum class Ic(val res: Int) {
    CAR(ai.chagok.app.R.drawable.ic_car), FUEL(ai.chagok.app.R.drawable.ic_fuel), PLUG(ai.chagok.app.R.drawable.ic_plug),
    ROAD(ai.chagok.app.R.drawable.ic_road), PARKING(ai.chagok.app.R.drawable.ic_parking), WRENCH(ai.chagok.app.R.drawable.ic_wrench),
    SHIELD(ai.chagok.app.R.drawable.ic_shield), RECEIPT(ai.chagok.app.R.drawable.ic_receipt), DOC(ai.chagok.app.R.drawable.ic_doc),
    DROP(ai.chagok.app.R.drawable.ic_drop), DOT(ai.chagok.app.R.drawable.ic_dot), PERSON(ai.chagok.app.R.drawable.ic_person),
    MIC(ai.chagok.app.R.drawable.ic_mic), CHECK(ai.chagok.app.R.drawable.ic_check), ALERT(ai.chagok.app.R.drawable.ic_alert),
    INFO(ai.chagok.app.R.drawable.ic_info), HOME(ai.chagok.app.R.drawable.ic_home), LEDGER(ai.chagok.app.R.drawable.ic_ledger),
    SWAP(ai.chagok.app.R.drawable.ic_swap), PERCENT(ai.chagok.app.R.drawable.ic_percent), PIN(ai.chagok.app.R.drawable.ic_pin),
    SLIDERS(ai.chagok.app.R.drawable.ic_sliders), RESET(ai.chagok.app.R.drawable.ic_reset),
    CHEVRON_DOWN(ai.chagok.app.R.drawable.ic_chevron_down), CHEVRON_UP(ai.chagok.app.R.drawable.ic_chevron_up),
    CHEVRON_LEFT(ai.chagok.app.R.drawable.ic_chevron_left), CHEVRON_RIGHT(ai.chagok.app.R.drawable.ic_chevron_right),
    PLUS(ai.chagok.app.R.drawable.ic_plus), ARROW_RIGHT(ai.chagok.app.R.drawable.ic_arrow_right),
}

val ai.chagok.app.model.ExpenseCategory.ic: Ic
    get() = when (this) {
        ai.chagok.app.model.ExpenseCategory.FUEL -> Ic.FUEL
        ai.chagok.app.model.ExpenseCategory.CHARGE -> Ic.PLUG
        ai.chagok.app.model.ExpenseCategory.TOLL -> Ic.ROAD
        ai.chagok.app.model.ExpenseCategory.PARKING -> Ic.PARKING
        ai.chagok.app.model.ExpenseCategory.MAINTENANCE -> Ic.WRENCH
        ai.chagok.app.model.ExpenseCategory.INSURANCE -> Ic.SHIELD
        ai.chagok.app.model.ExpenseCategory.TAX -> Ic.RECEIPT
        ai.chagok.app.model.ExpenseCategory.LEASE -> Ic.DOC
        ai.chagok.app.model.ExpenseCategory.WASH -> Ic.DROP
        ai.chagok.app.model.ExpenseCategory.OTHER -> Ic.DOT
    }

/** 아이콘 한 변(px). 큰 화면에서는 글자와 함께 키운다 */
fun Context.iconPx(sizeDp: Int): Int = (dp(sizeDp) * if (uiScale > 1f) LARGE_DISPLAY_ICON_SCALE else 1f).toInt()

fun Context.iconDrawable(ic: Ic, color: Int, sizeDp: Int): Drawable =
    getDrawable(ic.res)!!.mutate().apply {
        setTint(color)
        setBounds(0, 0, iconPx(sizeDp), iconPx(sizeDp))
    }

/**
 * 회색 외곽선 아이콘 (기본 20dp). add(…, WRAP, WRAP) 로 붙여도 크기가 벡터 원본(24dp)으로 돌아가지 않도록
 * 직접 잰다. 큰 화면에서는 [iconPx] 만큼 키운다.
 */
fun Context.icon(ic: Ic, color: Int = C.SUB, sizeDp: Int = 20): android.widget.ImageView {
    val px = iconPx(sizeDp)
    return object : android.widget.ImageView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(px, px)
    }.apply {
        setImageDrawable(iconDrawable(ic, color, sizeDp))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/**
 * 큰 차량 디스플레이(최소 변 1200dp 이상, 예: 17″ 16:9 2560×1440)는 화면이 멀고 넓어서
 * 같은 dp·sp 로는 글자와 버튼이 작게 보이고 아래가 비어 보인다. 이때만 전체를 1.25배로 키운다.
 * 휴대폰·기본 1920×1080 차량 화면(최소 변 1080dp)은 그대로다.
 */
const val LARGE_DISPLAY_MIN_DP = 1200
const val LARGE_DISPLAY_SCALE = 1.25f

/** 큰 화면의 최소 터치 높이. 운전 중 한 번에 누르도록 차량 가이드(76dp)보다 넉넉히 */
const val LARGE_DISPLAY_MIN_TOUCH_DP = 96

/** 큰 화면에서 아이콘은 글자 단계표만큼 함께 키운다 */
const val LARGE_DISPLAY_ICON_SCALE = 1.5f

val Context.uiScale: Float
    get() = if (resources.configuration.smallestScreenWidthDp >= LARGE_DISPLAY_MIN_DP) LARGE_DISPLAY_SCALE else 1f

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density * uiScale).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density * uiScale

/**
 * 글자 크기(sp). 코드에는 기본 화면 기준 값을 쓰고, 큰 화면(17″)에서는 운전석에서 한눈에 읽히도록
 * 역할별 단계표로 올린다: 보조 20 · 라벨 22 · 본문 26 · 행 제목·버튼 28 · 카드 제목 30 · 배너 32 · 제목·숫자 34.
 * 그보다 큰 값(핵심 숫자)은 1.5배.
 */
fun Context.sp(v: Float): Float = if (uiScale <= 1f) v else largeSp(v)

fun largeSp(v: Float): Float = when {
    v <= 14f -> 20f // 보조: 날짜·출처 줄, 각주
    v <= 15f -> 22f // 라벨: 칸 제목, 상태 줄
    v <= 17f -> 26f // 본문·목록
    v <= 18f -> 28f // 행 제목·버튼
    v <= 20f -> 30f // 카드 제목
    v <= 22f -> 32f // 배너 제목
    v <= 24f -> 34f // 페이지 제목·형제 숫자
    else -> v * LARGE_TYPE_SCALE
}

/** 핵심 숫자 배율 (큰 화면). 38sp → 57sp */
const val LARGE_TYPE_SCALE = 1.5f

/**
 * 좁은 창: 차량 화면을 세로 1칸으로 나눈 분할 창(예: 1920×1080 에서 628dp) 등.
 * 큰 화면 배율이 걸리면 그만큼 실제로 쓸 수 있는 폭이 줄어드므로 배율로 나눠 본다.
 */
val Context.isNarrow: Boolean
    get() = resources.configuration.screenWidthDp / uiScale < 900

/** 같은 폭 칸을 cols 개씩 줄 지어 놓는다. 마지막 줄이 모자라면 빈 칸으로 채워 폭을 맞춘다 */
fun Context.grid(cols: Int, items: List<View>, gapDp: Int = 12): LinearLayout = vbox {
    items.chunked(cols).forEachIndexed { r, row ->
        add(hbox(Gravity.TOP) {
            for (i in 0 until cols) {
                val end = if (i < cols - 1) gapDp else 0
                val v = row.getOrNull(i)
                // 빈 칸은 높이 0 으로 고정한다. WRAP 인 빈 View 는 남는 높이를 모두 먹는다 (divider 와 같은 함정)
                if (v != null) add(v, 0, WRAP, 1f, end = end) else add(View(context), 0, 0, 1f, end = end)
            }
        }, top = if (r > 0) gapDp else 0)
    }
}

/** 넓으면 나란히(폭 비율 weights, 높이 같게), 좁은 창이면 위아래로 놓는다 */
fun Context.pair(first: View, second: View, firstWeight: Float = 1f, secondWeight: Float = 1f, gapDp: Int = 12): ViewGroup =
    if (isNarrow) {
        vbox {
            add(first)
            add(second, top = gapDp)
        }
    } else {
        // 같은 줄 카드는 아래 끝을 맞춘다: 짧은 쪽을 긴 쪽 높이로 맞추고, addFiller() 로 합계·링크를 아래에 붙인다
        EqualRow(this, dp(gapDp), floatArrayOf(firstWeight, secondWeight)).apply {
            addView(first)
            addView(second)
        }
    }

/**
 * 폭은 무게대로 나누고, 높이는 가장 긴 칸에 맞춘다.
 * LinearLayout 의 MATCH_PARENT 높이는 스크롤 안(fillViewport)에서 화면 끝까지 늘어나 직접 잰다:
 * 먼저 제 높이(UNSPECIFIED)로 재고, 가장 긴 높이로 다시 EXACTLY 로 잰다.
 */
class EqualRow(context: Context, private val gap: Int, private val weights: FloatArray) : ViewGroup(context) {
    private fun widths(total: Int): IntArray {
        val free = total - gap * (childCount - 1)
        val sum = weights.take(childCount).sum()
        return IntArray(childCount) { (free * weights[it] / sum).toInt() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val ws = widths(w)
        var h = 0
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(ws[i], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            h = maxOf(h, getChildAt(i).measuredHeight)
        }
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(ws[i], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(x, 0, x + c.measuredWidth, c.measuredHeight)
            x += c.measuredWidth + gap
        }
    }
}

/** 버튼 최소 높이. 큰 화면에서는 배율을 곱한 값과 76dp 중 큰 쪽 */
fun Context.touchHeight(v: Int): Int =
    if (uiScale > 1f) maxOf(dp(v), (LARGE_DISPLAY_MIN_TOUCH_DP * resources.displayMetrics.density).toInt()) else dp(v)

fun Context.rounded(color: Int, radiusDp: Int = 16, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

fun Context.pressable(bg: Drawable): Drawable =
    RippleDrawable(ColorStateList.valueOf(C.RIPPLE), bg, bg)

fun Context.text(
    s: CharSequence,
    sp: Float = 18f,
    color: Int = C.TEXT,
    bold: Boolean = false,
    lines: Int = 0,
    /** 금액·거리·비율: 자릿수 폭을 같게 해 열에서 자리가 맞게 한다 */
    tabular: Boolean = false,
): TextView = TextView(this).apply {
    text = s
    if (tabular) fontFeatureSettings = "tnum"
    // 한국어를 어절 단위로 줄바꿈한다 (글자가 커져도 "기록/됩니다" 처럼 단어 중간에서 꺾이지 않게)
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(sp))
    setTextColor(color)
    if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
    includeFontPadding = false
    setLineSpacing(dpf(2f), 1f)
}

inline fun Context.vbox(gravity: Int = Gravity.NO_GRAVITY, block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
        block()
    }

inline fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL, block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        block()
    }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

fun LinearLayout.add(v: View, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, top: Int = 0, end: Int = 0): View {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.topMargin = context.dp(top)
    p.marginEnd = context.dp(end)
    addView(v, p)
    return v
}

fun Context.space(h: Int = 16): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

fun Context.card(bg: Int = C.SURFACE, padDp: Int = 22, stroke: Int? = null, block: LinearLayout.() -> Unit): LinearLayout =
    vbox {
        background = rounded(bg, 20, stroke)
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        block()
    }

enum class BtnStyle { PRIMARY, SECONDARY, GHOST, WARN }

/** 차량 터치 가이드에 맞춰 최소 높이 64dp (큰 화면은 76dp 이상) */
fun Context.button(label: String, style: BtnStyle = BtnStyle.SECONDARY, enabled: Boolean = true, icon: Ic? = null, onClick: () -> Unit): TextView =
    text(label, 18f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = touchHeight(64)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        val (bg, fg) = when (style) {
            BtnStyle.PRIMARY -> C.ACCENT to C.ON_ACCENT
            BtnStyle.SECONDARY -> C.SURFACE2 to C.TEXT
            BtnStyle.GHOST -> Color.TRANSPARENT to C.SUB
            BtnStyle.WARN -> C.AMBER to C.ON_AMBER
        }
        setTextColor(fg)
        if (icon != null) {
            setCompoundDrawablesRelative(iconDrawable(icon, fg, 20), null, null, null)
            compoundDrawablePadding = dp(8)
        }
        background = pressable(rounded(bg, 16, if (style == BtnStyle.GHOST) C.LINE else null))
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.35f
        setOnClickListener { onClick() }
    }

fun Context.chip(label: String, fg: Int, bg: Int, sp: Float = 14f): TextView =
    text(label, sp, fg, bold = true).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(bg, 999)
        gravity = Gravity.CENTER
    }

/** 가로 막대(비율 표시) */
fun Context.bar(fraction: Double, color: Int, heightDp: Int = 10): View {
    val f = fraction.coerceIn(0.0, 1.0).toFloat()
    return hbox {
        background = rounded(C.SURFACE2, 999)
        add(View(context).apply { background = rounded(color, 999) }, 0, dp(heightDp), f.coerceAtLeast(0.0001f))
        add(View(context), 0, dp(heightDp), (1f - f).coerceAtLeast(0.0001f))
    }
}

/**
 * 1dp 구분선. add()가 LayoutParams 를 (MATCH, WRAP)으로 덮어써도 높이가 1dp 로 고정되도록
 * 직접 잰다. 그냥 View 로 두면 WRAP 에서 남은 높이를 모두 차지해, 큰 화면(fillViewport)에서는
 * 회색 덩어리로, 작은 화면(스크롤 안)에서는 높이 0 으로 사라졌다.
 */
fun Context.divider(): View = object : View(this) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(1))
    }
}.apply {
    background = ColorDrawable(C.LINE)
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
}

/** 세로 1dp 구분선 (나란한 칸 사이) */
fun Context.vline(): View = object : View(this) {
    // 줄 높이가 정해질 때(EXACTLY)만 그 높이를 쓰고, 그 전에는 0. 그냥 크기를 따르면 화면 끝까지 늘어난다
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(dp(1), h)
    }
}.apply { background = ColorDrawable(C.LINE) }

/**
 * 세로 방향으로 남는 높이를 차지한다. 늘어난 카드에서 합계·링크를 아래 끝에 붙일 때 쓴다.
 * add() 는 높이를 WRAP 으로 덮어써 화면 끝까지 늘어나므로, 무게 1·높이 0 으로 직접 붙인다.
 */
fun LinearLayout.addFiller() = addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

/**
 * 요약 수치를 칸마다 카드로 나누지 않고, 카드 하나를 1px 선으로 나눈다.
 * [lead] 칸은 전체 폭을 쓰고, 나머지는 cols 개씩 같은 폭으로 놓는다 (홀수로 남는 칸 없이).
 */
fun Context.strip(cells: List<View>, cols: Int, lead: View? = null): LinearLayout = vbox {
    background = rounded(C.SURFACE, 20)
    val pad = dp(20)
    fun cell(v: View) = vbox { setPadding(pad, pad, pad, pad); addView(v) }
    if (lead != null) add(cell(lead))
    cells.chunked(cols).forEachIndexed { r, row ->
        if (lead != null || r > 0) add(divider())
        add(hbox(Gravity.TOP) {
            for (i in 0 until cols) {
                if (i > 0) add(vline(), WRAP, MATCH)
                val v = row.getOrNull(i)
                if (v != null) add(cell(v), 0, MATCH, 1f) else add(View(context), 0, 0, 1f)
            }
        })
    }
}

fun Context.frame(block: FrameLayout.() -> Unit): FrameLayout = FrameLayout(this).apply(block)
