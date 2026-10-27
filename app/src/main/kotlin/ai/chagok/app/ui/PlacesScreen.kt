package ai.chagok.app.ui

import ai.chagok.app.MainActivity
import ai.chagok.app.model.Place
import ai.chagok.app.model.PlaceKind
import android.text.InputType
import android.view.Gravity
import android.view.View
import java.util.Locale

/** 집·회사·거래처 지오펜스. 내비 목적지 없이 도착 위치만으로 업무 운행을 분류하는 기준 */
class PlacesScreen(private val a: MainActivity) {
    private val c = a.controller

    fun render(): View = a.vbox {
        add(a.card(C.SURFACE, padDp = 20) {
            add(a.text("자동 분류 규칙", 18f, bold = true))
            add(
                a.text(
                    "도착 위치가 거래처 반경 안 → 일반업무 · 거래처에서 회사로 복귀 → 일반업무 · 집 ↔ 회사 → 출퇴근 · 그 밖의 곳 → 도착 후 음성으로 한 번 묻기",
                    16f, C.SUB,
                ),
                top = 8,
            )
        })
        add(a.hbox {
            add(a.text("등록된 장소 ${c.places.size}곳", 20f, bold = true), 0, WRAP, 1f)
            add(a.button("+ 지금 차 위치를 거래처로", BtnStyle.PRIMARY) { addHere() }, WRAP, WRAP)
        }, top = 20)
        add(a.card {
            c.places.sortedBy { it.kind.ordinal }.forEachIndexed { i, p ->
                if (i > 0) add(a.divider(), top = 12)
                add(row(p), top = if (i == 0) 0 else 12)
            }
            if (c.places.isEmpty()) add(a.text("등록된 장소가 없어요", 17f, C.DIM))
        }, top = 12)
    }

    private fun row(p: Place): View = a.hbox {
        // 종류는 색 대신 아이콘과 글자로 구분한다
        val ic = when (p.kind) {
            PlaceKind.HOME -> Ic.HOME
            PlaceKind.OFFICE -> Ic.LEDGER
            PlaceKind.CLIENT -> Ic.PIN
        }
        add(a.icon(ic, C.SUB, 22), WRAP, WRAP, end = 14)
        add(a.text(p.kind.label, 15f, C.SUB), a.dp(64), WRAP, end = 8)
        add(a.vbox {
            add(a.text(p.name, 18f, bold = true))
            val visits = c.trips.count { it.toName == p.name }
            add(a.text("${p.address.ifEmpty { coord(p) }} · 반경 ${p.radiusM}m · 도착 ${visits}회", 14f, C.DIM, lines = 1), top = 4)
        }, 0, WRAP, 1f)
        add(a.button("삭제", BtnStyle.GHOST) {
            a.dialog("'${p.name}'을(를) 삭제할까요?", a.text("  기존 운행 기록은 그대로 남습니다.", 16f, C.SUB), "삭제") { c.deletePlace(p.id) }
        }, WRAP, WRAP)
    }

    private fun coord(p: Place) = String.format(Locale.US, "%.4f, %.4f", p.lat, p.lng)

    private fun addHere() {
        val (lat, lng) = c.currentLocation()
        var kind = PlaceKind.CLIENT
        val name = a.editText("이름 (예: 한빛물산 판교 본사)", InputType.TYPE_CLASS_TEXT)
        val radius = a.editText("반경 m", InputType.TYPE_CLASS_NUMBER, "300")
        val kinds = a.hbox()
        fun paint() {
            for (i in 0 until kinds.childCount) {
                val v = kinds.getChildAt(i)
                val sel = v.tag == kind
                v.background = a.rounded(if (sel) C.NAV_ACTIVE else C.SURFACE2, 999, if (sel) C.SUB else null)
            }
        }
        for (k in PlaceKind.values()) {
            kinds.add(a.chip(k.label, C.TEXT, C.SURFACE2, 16f).apply {
                tag = k
                minHeight = a.touchHeight(52)
                minWidth = a.dp(90)
                setOnClickListener { kind = k; paint() }
            }, WRAP, WRAP, end = 8)
        }
        paint()
        val body = a.vbox {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
            add(a.text("현재 위치 ${String.format(Locale.US, "%.5f, %.5f", lat, lng)} (FusedLocation)", 15f, C.SUB))
            add(kinds, top = 14)
            add(name, top = 14)
            add(a.hbox(Gravity.CENTER_VERTICAL) {
                add(radius, 0, WRAP, 1f, end = 10)
                add(a.text("반경 안에 도착하면 이 장소로 판단", 14f, C.DIM), 0, WRAP, 2f)
            }, top = 10)
        }
        a.dialog("장소 등록", body, "저장") {
            val n = name.text.toString().trim().ifEmpty { "${kind.label} ${c.places.size + 1}" }
            c.savePlace(Place(name = n, kind = kind, lat = lat, lng = lng, radiusM = radius.text.toString().toIntOrNull() ?: 300))
            a.toast("'$n' 등록 · 다음 도착부터 자동 분류됩니다")
        }
    }
}
