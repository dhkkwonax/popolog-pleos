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

/** 차량 디스플레이용 다크 테마. 운전 중 시선 분산을 줄이려 대비는 높이고 색은 의미 있는 곳에만 쓴다. */
object C {
    val BG = Color.parseColor("#0B0F14")
    val RAIL = Color.parseColor("#0F141B")
    val SURFACE = Color.parseColor("#161C24")
    val SURFACE2 = Color.parseColor("#1F2731")
    val LINE = Color.parseColor("#2A3441")
    val TEXT = Color.parseColor("#F2F5F8")
    val SUB = Color.parseColor("#97A3B3")
    val DIM = Color.parseColor("#66727F")
    val ACCENT = Color.parseColor("#2DD4BF")
    val ACCENT_BG = Color.parseColor("#123B39")
    val AMBER = Color.parseColor("#FBBF24")
    val AMBER_BG = Color.parseColor("#3A2E10")
    val RED = Color.parseColor("#F87171")
    val RED_BG = Color.parseColor("#3B1A1A")
    val BLUE = Color.parseColor("#7CB6FF")
    val BLUE_BG = Color.parseColor("#16273D")
    val VIOLET = Color.parseColor("#C4B5FD")
    val VIOLET_BG = Color.parseColor("#2A2342")
}

/**
 * 큰 차량 디스플레이(최소 변 1200dp 이상, 예: 17″ 16:9 2560×1440)는 화면이 멀고 넓어서
 * 같은 dp·sp 로는 글자와 버튼이 작게 보이고 아래가 비어 보인다. 이때만 전체를 1.25배로 키운다.
 * 휴대폰·기본 1920×1080 차량 화면(최소 변 1080dp)은 그대로다.
 */
const val LARGE_DISPLAY_MIN_DP = 1200
const val LARGE_DISPLAY_SCALE = 1.25f

/** 큰 화면에서 보조 글자가 이보다 작아지지 않게 한다 (운전석 시야 거리 기준) */
const val LARGE_DISPLAY_MIN_SP = 16f

/** 큰 화면의 최소 터치 높이 (차량 터치 가이드 76dp) */
const val LARGE_DISPLAY_MIN_TOUCH_DP = 76

val Context.uiScale: Float
    get() = if (resources.configuration.smallestScreenWidthDp >= LARGE_DISPLAY_MIN_DP) LARGE_DISPLAY_SCALE else 1f

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density * uiScale).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density * uiScale

/** 글자 크기(sp). 큰 화면에서는 배율을 곱하고 최소 16sp 를 보장한다 */
fun Context.sp(v: Float): Float = if (uiScale > 1f) maxOf(v * uiScale, LARGE_DISPLAY_MIN_SP) else v

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

/** 넓으면 나란히(폭 비율 weights), 좁은 창이면 위아래로 놓는다 */
fun Context.pair(first: View, second: View, firstWeight: Float = 1f, secondWeight: Float = 1f, gapDp: Int = 12): LinearLayout =
    if (isNarrow) {
        vbox {
            add(first)
            add(second, top = gapDp)
        }
    } else {
        hbox(Gravity.TOP) {
            add(first, 0, WRAP, firstWeight, end = gapDp)
            add(second, 0, WRAP, secondWeight)
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
    RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), bg, bg)

fun Context.text(
    s: CharSequence,
    sp: Float = 18f,
    color: Int = C.TEXT,
    bold: Boolean = false,
    lines: Int = 0,
): TextView = TextView(this).apply {
    text = s
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
fun Context.button(label: String, style: BtnStyle = BtnStyle.SECONDARY, enabled: Boolean = true, onClick: () -> Unit): TextView =
    text(label, 18f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = touchHeight(64)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        val (bg, fg) = when (style) {
            BtnStyle.PRIMARY -> C.ACCENT to Color.parseColor("#06201E")
            BtnStyle.SECONDARY -> C.SURFACE2 to C.TEXT
            BtnStyle.GHOST -> Color.TRANSPARENT to C.SUB
            BtnStyle.WARN -> C.AMBER to Color.parseColor("#2A1E00")
        }
        setTextColor(fg)
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

fun Context.frame(block: FrameLayout.() -> Unit): FrameLayout = FrameLayout(this).apply(block)
