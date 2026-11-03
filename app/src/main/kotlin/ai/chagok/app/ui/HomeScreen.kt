package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.TaxReport
import ai.chagok.app.model.DriverMethod
import ai.chagok.app.model.ExpenseCategory
import ai.chagok.app.model.TripPurpose
import ai.chagok.app.model.Powertrain
import android.view.Gravity
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeScreen(private val a: MainActivity) {
    private val c = a.controller

    /**
     * 홈이 먼저 답하는 질문: "이번 달 차 비용이 빠짐없이 기록되고 있나?"
     * 그래서 가장 큰 숫자는 이번 달 차량비, 바로 아래 한 줄은 지금 손볼 것(발견 사항)이다.
     */
    fun render(): View = a.vbox {
        // 홈은 페이지 제목 대신 차량·운전자 카드로 시작한다 (머리말의 작은 글자 줄들을 큰 타일로)
        add(vehicleCard())
        c.driverGreeting?.let { add(greeting(it), top = 14) }
        // 앱이 먼저 묻는 질문 (같은 기록을 묻는 아래 카드는 숨긴다)
        val asking = c.assistant.current?.takeIf { it.refType != "trip" || c.canEditTrip(it.refId) }
        asking?.let { add(a.assistantCard(it), top = 14) }
        add(statusCard(), top = 14)
        a.expiryBanner()?.let { banner -> add(a.card(C.SURFACE, stroke = C.AMBER) { add(banner) }, top = 14) }
        c.flows.dueItems().firstOrNull()?.let { add(maintenanceCard(it), top = 14) }
        c.pendingExpense()?.takeUnless { asking?.refType == "expense" && asking.refId == it.id }?.let { add(expensePrompt(it), top = 14) }
        c.pendingTrip()?.takeUnless { asking?.refType == "trip" && asking.refId == it.id }?.let { add(tripPrompt(it), top = 14) }
        c.currentTripId?.let { id -> c.trips.firstOrNull { it.id == id && c.canEditTrip(id) } }?.let { trip ->
            add(a.card {
                add(a.text("이번 운행 이용자", 18f, bold = true))
                add(a.text(if (trip.logbookUser.isBlank()) "아직 확인하지 않았어요" else "${trip.logbookDepartment} · ${trip.logbookUser}", 16f, C.SUB), top = 8)
                add(a.button("이번 운행 이용자 확인", BtnStyle.SECONDARY) { a.chooseLogbookUser(trip) }, WRAP, WRAP, top = 12)
            }, top = 14)
        }
        add(costCard(), top = 14)
        add(recent(), top = 14)
    }

    /** 차량·운전자 카드: 제목 줄(차종·번호판), 운전자, 차량 상태 3칸 */
    private fun vehicleCard(): View = a.card(padDp = 22) {
        val info = c.gateway.info
        // 제목 줄 오른쪽 빈자리는 시연 오버레이가 쓴다 (tag 로 찾음)
        add(a.hbox {
            tag = OVERLAY_SLOT
            add(a.text(info.title, 15f, C.SUB, lines = 1), 0, WRAP, 1f)
        })
        val driver = driverRow()
        val tiles = stateTiles()
        if (a.isNarrow) {
            add(driver, top = 14)
            add(a.divider(), top = 16)
            add(tiles, top = 16)
        } else {
            // 넓은 창: 운전자와 상태 3칸을 한 줄에
            add(a.hbox(Gravity.CENTER_VERTICAL) {
                add(driver, 0, WRAP, 1.3f, end = 20)
                add(a.vline(), WRAP, MATCH, end = 20)
                add(tiles, 0, WRAP, 2f)
            }, top = 14)
        }
    }

    private fun driverRow(): View = a.hbox(Gravity.CENTER_VERTICAL) {
        val d = c.driver
        add(a.icon(Ic.PERSON, C.SUB, 26), WRAP, WRAP, end = 14)
        add(a.vbox {
            // 실제 동작 모드는 예시 운전자가 없어, 아무도 등록하지 않았으면 설정으로 안내한다
            val none = c.drivers.isEmpty()
            add(a.text(d?.driver?.name ?: if (none) "운전자 미등록" else "운전자 확인 중", 20f, bold = true, lines = 1))
            if (d == null && none) add(a.text("설정에서 운전자를 추가하세요", 14f, C.SUB, lines = 1), top = 4)
            // "직접 선택" 은 받침이 있어 "~로 인식" 이 어색하다. 직접 고른 경우는 그대로 적는다
            if (d != null) add(a.text(if (d.method == DriverMethod.MANUAL) "직접 선택" else d.method.label + "로 인식", 14f, C.SUB, lines = 1), top = 4)
        }, 0, WRAP, 1f, end = 12)
        if (c.drivers.isEmpty()) add(a.button("추가", BtnStyle.SECONDARY) { a.select(MainActivity.Tab.SETTINGS) }, WRAP, WRAP)
        else add(a.button("바꾸기", BtnStyle.SECONDARY) { a.pickDriver() }.apply { contentDescription = "운전자 바꾸기" }, WRAP, WRAP)
    }

    /** P 주차 / 누적 km / 연료(배터리) % — 숫자는 크게, 단위·뜻은 라벨로 */
    private fun stateTiles(): View {
        val ev = c.gateway.info.powertrain == Powertrain.EV
        val low = c.energyPercent < 20
        fun tile(value: String, label: String, color: Int = C.TEXT) = a.vbox {
            add(a.text(value, 24f, color, bold = true, lines = 1, tabular = true))
            add(a.text(label, 15f, C.SUB, lines = 1), top = 4)
        }
        val drive = if (c.isDriving) tile("주행 중", "이번 운행 " + AppController.fmtKm(c.liveTripKm)) else tile("P", "주차 중")
        val odo = tile(String.format(Locale.US, "%,d", Math.round(c.odoKm)), "km 누적")
        val energy = tile("${Math.round(c.energyPercent)}%", if (ev) "배터리" else "연료", if (low) C.AMBER else C.TEXT)
        return a.hbox(Gravity.TOP) {
            add(drive, 0, WRAP, 1f, end = 12)
            add(odo, 0, WRAP, 1.2f, end = 12)
            add(energy, 0, WRAP, 1f)
        }
    }

    /** 발견 사항 + 확인할 항목을 한 장으로. 0건이면 초록 체크, 있으면 노란색으로 그 항목 바로 가기 */
    private fun statusCard(): View = a.card(padDp = 20) {
        val pendingTrip = c.trips.firstOrNull { it.purpose == TripPurpose.UNCLASSIFIED && c.canEditTrip(it.id) }
        val pendingExpense = c.expenses.firstOrNull { it.pendingAmount && c.canEditExpense(it.id) }
        val nTrips = c.trips.count { it.purpose == TripPurpose.UNCLASSIFIED }
        val nAmounts = c.expenses.count { it.pendingAmount }
        if (nTrips + nAmounts == 0) {
            add(a.hbox {
                add(a.icon(Ic.CHECK, C.POSITIVE, 24), WRAP, WRAP, end = 12)
                add(a.text("이번 달 기록이 모두 정리됐어요", 17f, C.TEXT), 0, WRAP, 1f)
            })
            return@card
        }
        val items = listOfNotNull(
            pendingExpense?.let { e -> "주유·충전 ${nAmounts}건 금액 입력 필요" to { a.askAmountByKeypad(e) } },
            pendingTrip?.let { t -> "운행 ${nTrips}건 목적 확인 필요" to { a.choosePurpose(t) } },
        )
        items.forEachIndexed { i, (label, go) ->
            if (i > 0) add(a.divider(), top = 12)
            add(a.hbox {
                add(a.icon(Ic.ALERT, C.AMBER, 24), WRAP, WRAP, end = 12)
                add(a.text(label, 17f, C.AMBER, bold = true), 0, WRAP, 1f, end = 8)
                add(a.icon(Ic.CHEVRON_RIGHT, C.AMBER, 22), WRAP, WRAP)
                minimumHeight = a.touchHeight(52)
                background = a.pressable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setOnClickListener { go() }
            }, top = if (i > 0) 12 else 0)
        }
    }

    /** 주행거리 기준 교체 주기가 된 정비 항목. 정비했다고 입력하면 이어서 기록한다 */
    private fun maintenanceCard(d: ai.chagok.app.core.MaintenancePlan.Due): View = a.card(C.SURFACE, stroke = C.AMBER) {
        add(a.hbox {
            add(a.icon(Ic.WRENCH, C.AMBER, 28), WRAP, WRAP, end = 16)
            add(a.vbox {
                add(a.text("${d.item.name} 교체 시기예요", 22f, bold = true))
                val last = d.lastOdo?.let { "마지막 ${AppController.fmtKm(it)}" } ?: "정비 기록 없음"
                add(a.text("$last · 주기 ${String.format(Locale.US, "%,d", d.item.intervalKm)}km", 16f, C.SUB), top = 6)
            }, 0, WRAP, 1f)
            add(a.button("정비 입력", BtnStyle.PRIMARY, icon = Ic.WRENCH) {
                a.showExpenseInput(ai.chagok.app.model.ExpenseDraft(ai.chagok.app.model.ExpenseCategory.MAINTENANCE, items = listOf(d.item.name.substringBefore(' '))))
            }, WRAP, WRAP)
        })
    }

    /** 시동 직후 잠깐: 누구로 인식했는지와 바꾸는 방법 */
    private fun greeting(text: String): View = a.card(C.SURFACE, stroke = C.LINE) {
        val d = c.driver
        val how = d?.let {
            val by = if (it.method == DriverMethod.MANUAL) "직접 선택으로 바꿈" else "${it.method.label}로 인식"
            listOf(by, it.driver.department).filter { s -> s.isNotEmpty() }.joinToString(" · ")
        }.orEmpty()
        val change = a.button("다른 분이에요", BtnStyle.SECONDARY) { a.pickDriver() }
        add(a.hbox {
            add(a.icon(Ic.PERSON, C.SUB, 28), WRAP, WRAP, end = 16)
            add(a.vbox {
                add(a.text(text, 22f, bold = true))
                add(a.text(how, 16f, C.SUB), top = 6)
            }, 0, WRAP, 1f)
            // 좁은 창에서는 버튼이 설명을 단어 중간에서 꺾으므로 아래 줄로 내린다
            if (!a.isNarrow) add(change, WRAP, WRAP)
        })
        if (a.isNarrow) add(change, top = 14)
    }

    private fun expensePrompt(e: ai.chagok.app.model.Expense): View = a.card(C.SURFACE, stroke = C.AMBER) {
        val unit = if (e.category == ExpenseCategory.CHARGE) "kWh" else "L"
        add(a.hbox {
            add(a.icon(e.category.ic, C.AMBER, 28), WRAP, WRAP, end = 16)
            add(a.vbox {
                // "주유가" / "충전이" — 받침 유무로 조사를 고른다
                val subject = if (e.category == ExpenseCategory.CHARGE) "충전이" else "주유가"
                add(a.text("$subject 감지됐어요 · 약 ${AppController.fmt1(e.quantity ?: 0.0)}$unit", 22f, bold = true))
                val gauge = if (e.category == ExpenseCategory.CHARGE) "배터리" else "연료"
                add(a.text("$gauge ${e.memo} · 결제 금액만 말씀하시면 장부에 들어갑니다", 16f, C.SUB), top = 6)
            }, 0, WRAP, 1f)
        })
        add(a.hbox {
            add(a.button("금액 말하기", BtnStyle.PRIMARY, icon = Ic.MIC) { c.flows.askAmount(e) }, 0, WRAP, 1.2f, end = 10)
            add(a.button("직접 입력") { a.askAmountByKeypad(e) }, 0, WRAP, 1f, end = 10)
            add(a.button("나중에", BtnStyle.GHOST) { c.dismissExpenseQuestion() }, 0, WRAP, 0.8f)
        }, top = 18)
    }

    private fun tripPrompt(t: ai.chagok.app.model.Trip): View = a.card(C.SURFACE, stroke = C.AMBER) {
        add(a.hbox {
            add(a.icon(Ic.CAR, C.AMBER, 28), WRAP, WRAP, end = 16)
            add(a.vbox {
                val gap = t.memo == AppController.GAP_MEMO
                add(a.text("${if (gap) "앱이 꺼진 동안 " else ""}${AppController.fmtKm(t.distanceKm)} 운행 · 업무였나요?", 22f, bold = true))
                add(
                    a.text(
                        (if (gap) "계기판으로 보정 · " else "${t.fromName.ifEmpty { "출발지" }} → ${t.toName.ifEmpty { "등록되지 않은 장소" }} · ") + "계기판 ${AppController.fmt1(t.startOdoKm)} → ${AppController.fmt1(t.endOdoKm)} km",
                        16f, C.SUB,
                    ),
                    top = 6,
                )
            }, 0, WRAP, 1f)
        })
        val choices = listOf(
            a.button("말하기", BtnStyle.PRIMARY, icon = Ic.MIC) { c.flows.askPurpose(t) },
            a.button("일반업무") { c.classifyTrip(t.id, TripPurpose.BUSINESS, ai.chagok.app.model.ClassifiedBy.MANUAL) },
            a.button("출퇴근") { c.classifyTrip(t.id, TripPurpose.COMMUTE, ai.chagok.app.model.ClassifiedBy.MANUAL) },
            a.button("비업무") { c.classifyTrip(t.id, TripPurpose.PERSONAL, ai.chagok.app.model.ClassifiedBy.MANUAL) },
        )
        // 좁은 창에서 네 칸 한 줄이면 큰 글자의 '말하기'가 '말하/기'로 꺾인다 → 2×2
        add(a.grid(if (a.isNarrow) 2 else 4, choices, gapDp = 10), top = 18)
    }

    /** 차량비 카드: 왼쪽에 이번 달 차량비(가장 큰 숫자), 오른쪽 빈자리에 주행·업무사용 타일 */
    private fun costCard(): View {
        val now = c.gateway.now()
        val cal = Calendar.getInstance(TaxReport.KST).apply { timeInMillis = now }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH)
        fun sameMonth(t: Long) = Calendar.getInstance(TaxReport.KST).apply { timeInMillis = t }.let {
            it.get(Calendar.YEAR) == y && it.get(Calendar.MONTH) == m
        }
        val monthCost = c.expenses.filter { sameMonth(it.time) && !it.pendingAmount }.sumOf { it.amountWon }
        val monthKm = c.trips.filter { sameMonth(it.startTime) }.sumOf { it.distanceKm }
        val year = TaxReport.summarize(y, c.trips, c.expenses)
        val perKm = if (monthKm > 0) monthCost / monthKm else 0.0

        val lead = kpi("${m + 1}월 차량비", AppController.won(monthCost), "km당 ${Math.round(perKm)}원", LEAD_SP) { a.select(MainActivity.Tab.LEDGER) }
        val km = kpi("${m + 1}월 주행", AppController.fmtKm(monthKm), "계기판 누적거리 기준") { a.select(MainActivity.Tab.LOGBOOK) }
        val ratio = kpi("업무사용", String.format(Locale.US, "%.1f%%", year.businessRatio * 100), "${y}년 · 출퇴근 포함") { a.select(MainActivity.Tab.TAX) }
        val pad = a.dp(22)
        fun cell(v: View) = a.vbox { setPadding(pad, pad, pad, pad); addView(v) }
        return a.hbox(Gravity.TOP) {
            background = a.rounded(C.SURFACE, 20)
            add(cell(lead).apply { gravity = Gravity.CENTER_VERTICAL }, 0, MATCH, if (a.isNarrow) 1.25f else 1.4f)
            add(a.vline(), WRAP, MATCH)
            if (a.isNarrow) {
                // 좁은 창: 오른쪽에 두 타일을 세로로
                add(a.vbox {
                    add(cell(km))
                    add(a.divider())
                    add(cell(ratio))
                }, 0, WRAP, 1f)
            } else {
                // 넓은 창: 세 칸 한 줄
                add(cell(km), 0, MATCH, 1f)
                add(a.vline(), WRAP, MATCH)
                add(cell(ratio), 0, MATCH, 1f)
            }
        }
    }

    private fun kpi(label: String, value: String, sub: String, valueSp: Float = SIBLING_SP, color: Int = C.TEXT, onClick: () -> Unit): View =
        a.vbox {
            add(a.text(label, 15f, C.SUB, lines = 1))
            add(a.text(value, valueSp, color, bold = true, lines = 1, tabular = true), top = 8)
            add(a.text(sub, 14f, C.DIM), top = 6)
            background = a.pressable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setOnClickListener { onClick() }
        }

    /** 최근 기록 5건과 전체 보기. 정상 값은 잉크, 처리할 것만 색 */
    private fun recent(): View = a.card {
        add(a.hbox {
            add(a.text("최근 자동 기록", 20f, bold = true), 0, WRAP, 1f)
            add(a.text("운행일지 전체 보기", 16f, C.SUB).apply {
                setCompoundDrawablesRelative(null, null, a.iconDrawable(Ic.CHEVRON_RIGHT, C.SUB, 18), null)
                compoundDrawablePadding = a.dp(4)
                setPadding(a.dp(8), a.dp(8), 0, a.dp(8))
                setOnClickListener { a.select(MainActivity.Tab.LOGBOOK) }
            }, WRAP, WRAP)
        })
        data class Row(val time: Long, val icon: Ic, val title: String, val sub: String, val right: String, val color: Int)
        val rows = c.trips.filter { c.canManage || it.id == c.currentTripId }.take(RECENT_ROWS).map {
            Row(
                it.startTime, Ic.CAR, "${it.fromName.ifEmpty { "-" }} → ${it.toName.ifEmpty { "미등록 장소" }}",
                "${it.purpose.label} · ${it.classifiedBy.label}", AppController.fmtKm(it.distanceKm), purposeColor(it.purpose),
            )
        } + c.expenses.filter { c.canManage }.take(RECENT_ROWS).map {
            Row(
                it.time, it.category.ic, it.category.label + if (it.memo.isNotEmpty()) " · ${it.memo}" else "",
                it.source.label, if (it.pendingAmount) "금액 입력 필요" else AppController.won(it.amountWon),
                if (it.pendingAmount) C.AMBER else C.TEXT,
            )
        }
        val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREAN).apply { timeZone = TaxReport.KST }
        rows.sortedByDescending { it.time }.take(RECENT_ROWS).forEachIndexed { i, r ->
            if (i > 0) add(a.divider(), top = 12)
            add(a.hbox {
                add(a.icon(r.icon, C.SUB, 20), WRAP, WRAP, end = 16)
                add(a.vbox {
                    add(a.text(r.title, 17f))
                    add(a.text("${fmt.format(r.time)} · ${r.sub}", 14f, C.DIM), top = 4)
                }, 0, WRAP, 1f)
                add(a.text(r.right, 18f, r.color, bold = true, tabular = true), WRAP, WRAP)
            }, top = 12)
        }
    }

    companion object {
        // 큰 화면에서 핵심 48sp · 형제 30sp (1.6배). 기본 화면은 38 · 24
        private const val LEAD_SP = 38f
        private const val SIBLING_SP = 24f
        private const val RECENT_ROWS = 5

        /** 시연 오버레이가 놓일 자리 (차량 카드 제목 줄 오른쪽) */
        const val OVERLAY_SLOT = "overlay-slot"

        /** 운행 목적 표시: 정상은 잉크, 손봐야 할 미분류만 색 */
        fun purposeColor(p: TripPurpose) = if (p == TripPurpose.UNCLASSIFIED) C.AMBER else C.TEXT

        fun purposeBg(p: TripPurpose) = if (p == TripPurpose.UNCLASSIFIED) C.AMBER_BG else C.SURFACE2
    }
}
