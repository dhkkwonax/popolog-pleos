package ai.chagok.app.ui

import ai.chagok.app.core.AdCreative
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 주행 중 전체 화면 광고. 정지 이미지 한 장이며 애니메이션·터치 동작이 없다.
 * 우상단 "광고" 라벨과 좌상단 "이 광고는 Popolog를 통해 제공됩니다" 문구는 항상 보인다. 자동으로 돌아가지 않으며,
 * 하단의 "앱 화면으로" 버튼은 정차 여부와 상관없이 항상 보이고 눌러서 앱 화면으로 갈 수 있다.
 */
object AdScreen {
    fun build(context: Context, ad: AdCreative, night: Boolean, onDismiss: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val fg = if (night) Color.parseColor("#E6E6E6") else Color.parseColor("#1A1A1A")
        val dim = if (night) Color.parseColor("#9A9A9A") else Color.parseColor("#555555")
        fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        return FrameLayout(context).apply {
            setBackgroundColor(if (night) ad.bgNight else ad.bgDay)
            isClickable = true // 아래 일반 화면으로 터치가 새지 않게
            contentDescription = "광고 화면"
            val center = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(label(ad.headline, 40f, fg, bold = true).apply { gravity = Gravity.CENTER })
                addView(label(ad.sub, 22f, dim).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
            }
            addView(center, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(48), dp(48), dp(48), dp(48)) })
            addView(label("이 광고는 Popolog를 통해 제공됩니다", 16f, fg, bold = true).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundColor(if (night) Color.parseColor("#33000000") else Color.parseColor("#33FFFFFF"))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(20), dp(20), dp(20), dp(20)) })
            addView(label("광고 · ${ad.sponsor}", 16f, fg, bold = true).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundColor(if (night) Color.parseColor("#33000000") else Color.parseColor("#33FFFFFF"))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(20), dp(20), dp(20), dp(20)) })
            addView(label("앱 화면으로", 20f, fg, bold = true).apply {
                setPadding(dp(32), dp(14), dp(32), dp(14))
                setBackgroundColor(if (night) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000"))
                setOnClickListener { onDismiss() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        }
    }
}
