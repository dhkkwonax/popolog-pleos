package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.TaxReport
import ai.chagok.app.model.ClassifiedBy
import ai.chagok.app.model.Trip
import ai.chagok.app.model.TripPurpose
import android.view.Gravity
import android.view.View
import java.text.SimpleDateFormat
import java.util.Locale

class LogbookScreen(private val a: MainActivity) {
    private val c = a.controller

    fun render(): View = a.vbox {
        val range = MonthRange.of(c.gateway.now(), a.monthOffset)
        val list = c.trips.filter { range.contains(it.startTime) }
        add(a.monthHeader(range, a.button("세무 리포트 →", BtnStyle.SECONDARY) { a.select(MainActivity.Tab.TAX) }))

        val total = list.sumOf { it.distanceKm }
        fun km(p: TripPurpose) = list.filter { it.purpose == p }.sumOf { it.distanceKm }
        val un = list.count { it.purpose == TripPurpose.UNCLASSIFIED }
        // 값은 모두 잉크. 색은 손봐야 할 미분류에만
        val totalCell = stat("총 주행", AppController.fmtKm(total), "${list.size}회 · 계기판 기준")
        val parts = listOf(
            stat("일반업무", AppController.fmtKm(km(TripPurpose.BUSINESS)), "거래처 도착 자동 분류"),
            stat("출퇴근", AppController.fmtKm(km(TripPurpose.COMMUTE)), "집 ↔ 회사"),
            stat("비업무", AppController.fmtKm(km(TripPurpose.PERSONAL)), "개인 용무"),
            stat("미분류", "${un}건", if (un > 0) "눌러서 목적 지정" else "없음", if (un > 0) C.AMBER else C.TEXT),
        )
        // 좁은 창: 총 주행을 전체 폭에, 나머지 넷을 2×2 (빈 칸 없이) / 넓은 창: 다섯 칸 한 줄
        add(if (a.isNarrow) a.strip(parts, 2, totalCell) else a.strip(listOf(totalCell) + parts, 5), top = 16)

        add(a.card {
            add(a.hbox {
                add(a.text("운행 기록", 20f, bold = true), 0, WRAP, 1f)
                add(a.text("주행 전·후 값은 차량 계기판 누적거리 (Pleos Odometer)", 14f, C.DIM), WRAP, WRAP)
            })
            if (list.isEmpty()) add(a.text("이 달에는 운행 기록이 없어요", 17f, C.DIM), top = 16)
            val fmt = SimpleDateFormat("M/d (E)", Locale.KOREAN).apply { timeZone = TaxReport.KST }
            val hm = SimpleDateFormat("HH:mm", Locale.KOREAN).apply { timeZone = TaxReport.KST }
            list.forEach { t ->
                add(a.divider(), top = 12)
                add(row(t, fmt, hm), top = 12)
            }
        }, top = 16)
    }

    private fun stat(label: String, value: String, sub: String, color: Int = C.TEXT): View = a.vbox {
        add(a.text(label, 15f, C.SUB, lines = 1))
        add(a.text(value, 21f, color, bold = true, lines = 1, tabular = true), top = 8)
        add(a.text(sub, 14f, C.DIM, lines = 1), top = 6)
    }

    private fun row(t: Trip, fmt: SimpleDateFormat, hm: SimpleDateFormat): View = a.hbox {
        val narrow = a.isNarrow
        // 좁은 창에서는 날짜 칸(120dp)이 경로를 "대성정밀 (수원) → 미…" 로 자르므로 날짜를 경로 위로 올린다
        if (!narrow) {
            add(a.vbox {
                add(a.text(fmt.format(t.startTime), 16f, bold = true))
                add(a.text("${hm.format(t.startTime)}–${hm.format(t.endTime)}", 14f, C.DIM), top = 4)
            }, a.dp(120), WRAP)
        }
        add(a.vbox {
            if (narrow) add(a.text("${fmt.format(t.startTime)} ${hm.format(t.startTime)}–${hm.format(t.endTime)}", 14f, C.DIM))
            add(a.text("${t.fromName.ifEmpty { "-" }} → ${t.toName.ifEmpty { "미등록 장소" }}", 17f, lines = 1), top = if (narrow) 4 else 0)
            // 운행기록부의 성명 칸에 들어가는 운전자를 함께 보여 준다
            val who = if (t.driver.isNotEmpty()) " · ${t.driver}" else ""
            add(a.text("${AppController.fmt1(t.startOdoKm)} → ${AppController.fmt1(t.endOdoKm)} km" + (if (t.classifiedBy != ClassifiedBy.NONE) " · ${t.classifiedBy.label} 분류" else "") + who, 14f, C.DIM, lines = 1), top = 4)
        }, 0, WRAP, 1f)
        add(a.text(AppController.fmtKm(t.distanceKm), 18f, bold = true, tabular = true).apply { gravity = Gravity.END }, a.dp(if (narrow) 90 else 120), WRAP, end = 14)
        add(
            a.chip(t.purpose.label + " ▾", HomeScreen.purposeColor(t.purpose), HomeScreen.purposeBg(t.purpose), 15f).apply {
                minWidth = a.dp(110)
                minHeight = a.touchHeight(48)
                setOnClickListener { a.choosePurpose(t) }
            },
            WRAP, WRAP,
        )
    }
}
