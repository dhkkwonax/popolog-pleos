package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.Taxpayer
import android.view.Gravity
import android.text.InputType
import android.view.View

class SettingsScreen(private val a: MainActivity) {
    private val c = a.controller

    fun render(): View = a.vbox {
        val taxpayer = c.taxpayer
        val company = a.editText(taxpayer.nameLabel, InputType.TYPE_CLASS_TEXT, c.db.setting(AppController.KEY_COMPANY))
        val bizNo = a.editText("사업자등록번호", InputType.TYPE_CLASS_TEXT, c.db.setting(AppController.KEY_BIZNO))

        add(a.card {
            add(a.text("운행기록부 머리말", 20f, bold = true))
            add(a.text("${taxpayer.law}용 운행기록부의 ${taxpayer.nameLabel}·사업자번호 칸에 들어갑니다", 15f, C.SUB), top = 6)
            add(a.hbox {
                for (t in Taxpayer.values()) {
                    add(a.choice("${t.label} (${t.law})", taxpayer == t) { c.setTaxpayer(t); a.render() }, WRAP, WRAP, end = 10)
                }
            }, top = 12)
            add(a.hbox {
                add(company, 0, WRAP, 1f, end = 10)
                add(bizNo, 0, WRAP, 1f)
            }, top = 14)
            add(a.button("저장", BtnStyle.PRIMARY) {
                c.db.setSetting(AppController.KEY_COMPANY, company.text.toString())
                c.db.setSetting(AppController.KEY_BIZNO, bizNo.text.toString())
                a.toast("저장했어요")
            }, WRAP, WRAP, top = 14)
        })

        add(a.card {
            add(a.text("운전자·차량 이용자", 20f, bold = true))
            add(a.text("운전자는 시트 위치·운행 습관으로 추정합니다. 세무 기록의 차량 이용자는 운행별로 따로 확인합니다.", 15f, C.SUB), top = 6)
            val now = c.driver
            c.drivers.forEachIndexed { i, d ->
                if (i > 0) add(a.divider(), top = 12)
                add(a.hbox {
                    add(a.vbox {
                        add(a.text(d.name + if (now?.driver?.id == d.id) "  · 지금 운전 중" else "", 18f, bold = true))
                        add(a.text("${d.department} · 시트 위치 ${d.seatPosition?.toString() ?: "미등록"}", 15f, C.SUB), top = 4)
                    }, 0, WRAP, 1f)
                    add(a.button("지금 시트 위치로 등록", BtnStyle.GHOST) {
                        c.saveSeatFor(d.id)
                        a.toast("${d.name}님 시트 위치를 저장했어요")
                    }, WRAP, WRAP)
                }, top = 12)
            }
            // 실제 동작 모드는 예시 운전자가 없으므로 여기서 등록한다
            add(a.button("이용자·운전자 추가", BtnStyle.SECONDARY, icon = Ic.PERSON) { addDriverDialog() }, WRAP, WRAP, top = 14)
        }, top = 16)

        add(a.card {
            add(a.text("음성", 20f, bold = true))
            add(a.text("앱이 먼저 묻고 대답을 듣는 방식: ${c.voice.engine}", 15f, C.SUB), top = 6)
            add(a.text(if (c.voice.hearsVoice) "마이크로 듣습니다" else "이 환경에서는 마이크로 듣지 못해 화면 입력으로 대신합니다", 14f, C.DIM), top = 2)
            add(a.button("음성 점검", BtnStyle.SECONDARY, icon = Ic.MIC) {
                c.flows.voiceCheck { msg -> a.toast(msg) }
                a.select(MainActivity.Tab.HOME)
            }, WRAP, WRAP, top = 14)
        }, top = 16)

        add(a.card {
            add(a.text("정비 주기", 20f, bold = true))
            add(a.text("계기판 거리가 주기를 넘으면 차를 세웠을 때 알려 드려요", 15f, C.SUB), top = 6)
            val plan = c.flows.plan()
            plan.forEachIndexed { i, item ->
                if (i > 0) add(a.divider(), top = 12)
                add(a.hbox(Gravity.CENTER_VERTICAL) {
                    add(a.vbox {
                        add(a.text(item.name, 18f, bold = true))
                        val last = c.flows.lastDoneOdo(item.name)?.let { "마지막 ${AppController.fmtKm(it)}" } ?: "정비 기록 없음"
                        add(a.text("${String.format(java.util.Locale.US, "%,d", item.intervalKm)}km마다 · $last", 15f, C.SUB), top = 4)
                    }, 0, WRAP, 1f)
                    add(a.button("주기 수정", BtnStyle.GHOST) {
                        a.askAmountPad("${item.name} 교체 주기", unit = "km") { km ->
                            c.flows.savePlan(plan.map { if (it.name == item.name) it.copy(intervalKm = km.toInt()) else it })
                            a.render()
                        }
                    }, WRAP, WRAP, end = 8)
                    if (plan.size > 1) add(a.button("삭제", BtnStyle.GHOST) {
                        c.flows.savePlan(plan.filter { it.name != item.name })
                        a.render()
                    }, WRAP, WRAP)
                }, top = 12)
            }
            add(a.button("항목 추가", BtnStyle.SECONDARY, icon = Ic.PLUS) {
                a.askTextInput("정비 항목 이름", "예: 에어컨 필터") { name ->
                    a.askAmountPad("$name 교체 주기", unit = "km") { km ->
                        c.flows.savePlan(plan + ai.chagok.app.core.MaintItem(name, km.toInt()))
                        a.render()
                    }
                }
            }, WRAP, WRAP, top = 14)
        }, top = 16)

        add(a.card {
            add(a.text("화면 테마", 20f, bold = true))
            add(a.text("자동은 차량(시스템)의 주간·야간 신호를 따릅니다", 15f, C.SUB), top = 6)
            val now = c.db.setting(AppController.KEY_THEME, MainActivity.THEME_AUTO)
            val opts = listOf(MainActivity.THEME_AUTO to "자동", MainActivity.THEME_DAY to "주간", MainActivity.THEME_NIGHT to "야간")
            add(a.hbox {
                opts.forEachIndexed { i, (k, label) ->
                    add(a.choice(label, now == k) { a.applyThemeMode(k) }, WRAP, WRAP, end = if (i < opts.lastIndex) 10 else 0)
                }
            }, top = 14)
        }, top = 16)

        add(a.card {
            add(a.text("개인정보", 20f, bold = true))
            add(a.text("기록은 이 기기에만 저장합니다. 밖으로 나가는 것과 삭제 방법은 처리 안내에서 확인하세요 (초안)", 15f, C.SUB), top = 6)
            add(a.button("개인정보 처리 안내 보기", BtnStyle.SECONDARY) { a.showPrivacyNotice() }, WRAP, WRAP, top = 14)
            val tsaOn = c.tsaEnabled
            add(a.text("외부 타임스탬프 전송", 17f, bold = true), top = 18)
            add(a.text("하루가 끝나면 날짜와 해시의 지문만 freetsa.org에 보내 서명을 받습니다. 위치·이름·금액은 보내지 않지만 기관은 접속 시각과 IP를 알 수 있습니다.", 14f, C.DIM), top = 4)
            add(a.hbox {
                add(a.choice("켜짐", tsaOn) { c.setTsaEnabled(true); a.render() }, WRAP, WRAP, end = 10)
                add(a.choice("꺼짐", !tsaOn) { c.setTsaEnabled(false); a.render() }, WRAP, WRAP)
            }, top = 10)
            if (!c.demo) {
                add(a.divider(), top = 18)
                add(a.text("내 데이터 모두 삭제", 17f, bold = true), top = 14)
                add(a.text("운행·비용·장소·운전자·해시체인·앵커·설정을 모두 지웁니다. 되돌릴 수 없고, 내보낸 PDF·CSV 파일은 직접 지워야 합니다.", 14f, C.DIM), top = 4)
                add(a.button("모두 삭제", BtnStyle.WARN, icon = Ic.RESET) {
                    a.dialog(
                        "모든 기록을 삭제할까요?",
                        a.text("  세무 보존이 필요한 기록이면 먼저 운행기록부를 내보내세요. 삭제한 기록은 복구할 수 없습니다.", 16f, C.SUB),
                        "삭제",
                    ) {
                        c.wipeAllData()
                        a.toast("모든 기록을 삭제했어요")
                    }
                }, WRAP, WRAP, top = 12)
            }
        }, top = 16)

        add(a.card {
            add(a.text("차량 연동", 20f, bold = true))
            val info = c.gateway.info
            fun line(k: String, v: String) = a.hbox {
                add(a.text(k, 16f, C.SUB), a.dp(200), WRAP)
                add(a.text(v, 17f), 0, WRAP, 1f)
            }
            add(line("차량", info.title), top = 14)
            add(line("차대번호", info.vin), top = 10)
            add(line("동작 모드", if (c.demo) "데모 모드 (가상 차량·예시 데이터·시연 컨트롤)" else "실제 동작 모드"), top = 10)
            add(line("데이터 출처", if (c.gateway.isSimulated) "시뮬레이터 (Pleos SDK 미연결 기기)" else "Pleos Vehicle · FusedLocation SDK"), top = 10)
            add(line("사용하는 SDK", "Odometer · Fuel/EvBattery · Brake · CarInfo · FusedLocation · STT · TTS"), top = 10)
        }, top = 16)

        add(a.text("Popolog ${ai.chagok.app.BuildConfig.VERSION_NAME} · 현대자동차그룹 Pleos 앱 공모전 출품용 프로토타입", 14f, C.DIM), top = 20)
    }

    private fun addDriverDialog() {
        val name = a.editText("이름", InputType.TYPE_CLASS_TEXT)
        val dept = a.editText("부서", InputType.TYPE_CLASS_TEXT, c.db.setting(AppController.KEY_DEPT))
        val body = a.vbox {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
            add(name)
            add(dept, top = 10)
            add(a.text("시트 위치는 그 사람이 운전석에 앉은 뒤 '지금 시트 위치로 등록'으로 저장합니다", 14f, C.DIM), top = 10)
        }
        a.dialog("차량 이용자·운전자 추가", body, "추가") {
            val n = name.text.toString().trim()
            if (n.isNotEmpty()) c.addDriver(n, dept.text.toString().trim())
        }
    }
}
