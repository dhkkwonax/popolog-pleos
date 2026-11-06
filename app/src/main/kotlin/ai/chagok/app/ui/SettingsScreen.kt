package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import ai.chagok.app.core.Taxpayer
import android.view.Gravity
import android.text.InputType
import android.view.View
import android.widget.ScrollView

class SettingsScreen(private val a: MainActivity) {
    private val c = a.controller

    fun render(): View = a.vbox {
        add(a.adminControls(), top = 16)
        add(a.backupControls(), top = 16)
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
        }, top = 16)

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
                        add(a.text(if (c.hasConsent(d)) "위치·운전자 추정 동의함" else "위치·운전자 추정 동의 전 · 자동 추정에서 제외", 14f, if (c.hasConsent(d)) C.DIM else C.AMBER), top = 4)
                    }, 0, WRAP, 1f)
                    if (c.hasConsent(d)) {
                        add(a.button("지금 시트 위치로 등록", BtnStyle.GHOST) {
                            c.saveSeatFor(d.id)
                            a.toast("${d.name}님 시트 위치를 저장했어요")
                        }, WRAP, WRAP)
                        if (!c.demo) add(a.button("동의 철회", BtnStyle.GHOST) { c.setDriverConsent(d.id, false); a.render() }, WRAP, WRAP)
                    } else add(a.button("동의 안내 보기", BtnStyle.SECONDARY) { consentDialog(d) }, WRAP, WRAP)
                }, top = 12)
            }
            // 실제 동작 모드는 예시 운전자가 없으므로 여기서 등록한다
            add(a.button("이용자·운전자 추가", BtnStyle.SECONDARY, icon = Ic.PERSON) { addDriverDialog() }, WRAP, WRAP, top = 14)
        }, top = 16)

        add(a.card {
            add(a.text("음성", 20f, bold = true))
            add(a.text("앱이 먼저 묻고 대답을 듣는 방식: ${c.voice.engine}", 15f, C.SUB), top = 6)
            add(a.text(if (c.voice.hearsVoice) "마이크로 듣습니다" else "이 환경에서는 마이크로 듣지 못해 화면 입력으로 대신합니다", 14f, C.DIM), top = 2)
            if (!c.demo) {
                add(a.text("선택 사용입니다. 사용하지 않으면 모든 질문을 화면 버튼·입력으로 답하고, 차량 음성 서비스로 음성을 보내지 않습니다. 음성 원본·인식문은 앱이 따로 보관하지 않습니다.", 14f, C.DIM), top = 8)
                add(a.hbox {
                    add(a.choice("사용", c.voiceEnabled) { c.setVoiceEnabled(true); a.render() }, WRAP, WRAP, end = 10)
                    add(a.choice("사용 안 함", !c.voiceEnabled) { c.setVoiceEnabled(false); a.render() }, WRAP, WRAP)
                }, top = 12)
            }
            add(a.button("음성 점검", BtnStyle.SECONDARY, icon = Ic.MIC) {
                c.flows.voiceCheck { msg -> a.toast(msg) }
                a.select(MainActivity.Tab.HOME)
            }, WRAP, WRAP, top = 14)
        }, top = 16)

        add(a.card {
            add(a.text("광고 모드", 20f, bold = true))
            add(a.text("켜면 출발 후 이 앱 화면이 광고 이미지 한 장으로 바뀝니다. 주행 중에는 바뀌지 않고, 광고 화면 아래의 \"앱 화면으로\" 버튼을 누르면 언제든 앱 화면으로 갑니다. 내비·계기판은 따로 표시됩니다.", 15f, C.SUB), top = 6)
            add(a.text("모두 선택 사항(기본 꺼짐)이고, 켜고 끄는 건 주행 중에도 언제든 직접 고를 수 있습니다. \"광고 그만\"이라고 말하거나 여기서 끄면 바로 멈춥니다. 광고는 운행 기록·장부·세무 리포트에 들어가지 않습니다.", 14f, C.DIM), top = 8)
            add(a.hbox {
                add(a.choice("사용", c.adEnabled) {
                    if (c.adConsented) { c.setAdMode(true); a.render() } else a.showAdConsent()
                }, WRAP, WRAP, end = 10)
                add(a.choice("사용 안 함", !c.adEnabled) { c.setAdMode(false); a.render() }, WRAP, WRAP)
            }, top = 12)
            add(a.text("소리 광고", 16f, bold = true), top = 14)
            add(a.text("광고 모드를 켜면 광고를 소리로도 안내합니다(기본 켜짐). 횟수 제한은 없고, 정차 때만 나옵니다.", 14f, C.DIM), top = 4)
            add(a.hbox {
                add(a.choice("사용", c.adSoundEnabled) { c.setAdSound(true); a.render() }, WRAP, WRAP, end = 10)
                add(a.choice("사용 안 함", !c.adSoundEnabled) { c.setAdSound(false); a.render() }, WRAP, WRAP)
            }, top = 8)
            add(a.text("켜기 전에 알아두세요", 15f, bold = true), top = 14)
            add(a.text(ai.chagok.app.core.AdNotice.RISK, 14f, C.DIM), top = 4)
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
            add(a.text("기록은 이 기기에만 저장합니다. 밖으로 나가는 것과 삭제 방법은 처리 안내에서 확인하세요", 15f, C.SUB), top = 6)
            add(a.button("개인정보 처리 안내 보기", BtnStyle.SECONDARY) { a.showPrivacyNotice() }, WRAP, WRAP, top = 14)
            add(a.text("문의·권리 행사 연락처", 17f, bold = true), top = 18)
            add(a.text("운영 주체(사업장)가 지정하는 담당 연락처입니다. 처리 안내에 표시되며 관리자가 입력합니다.", 14f, C.DIM), top = 4)
            val contact = a.editText("예: 총무팀 02-000-0000 / privacy@example.com", InputType.TYPE_CLASS_TEXT, c.db.setting(AppController.KEY_PRIVACY_CONTACT))
            add(contact, top = 8)
            add(a.button("연락처 저장", BtnStyle.SECONDARY) {
                if (!a.requireAdmin()) return@button
                c.db.setSetting(AppController.KEY_PRIVACY_CONTACT, contact.text.toString().trim())
                a.toast("저장했어요")
            }, WRAP, WRAP, top = 10)
            val tsaOn = c.tsaEnabled
            add(a.text("외부 타임스탬프 전송", 17f, bold = true), top = 18)
            add(a.text("하루 마감 시 기록 지문을, PDF 내보내기 시 PDF 지문을 기관에 보내 서명을 받습니다. 기록·PDF 원문은 보내지 않지만 기관은 접속 시각과 IP를 알 수 있습니다. 끄면 새 PDF는 증명 없이 저장됩니다.", 14f, C.DIM), top = 4)
            add(a.hbox {
                add(a.choice("켜짐", tsaOn) { if (a.requireAdmin()) { c.setTsaEnabled(true); a.render() } }, WRAP, WRAP, end = 10)
                add(a.choice("꺼짐", !tsaOn) {
                    if (!c.admin.configured) a.toast("관리자를 설정한 뒤에 끌 수 있어요")
                    else if (a.requireAdmin()) { c.setTsaEnabled(false); a.render() }
                }, WRAP, WRAP)
            }, top = 10)
            add(a.text(if (c.admin.configured) "켜기·끄기는 관리자 PIN 인증 뒤에만 바꿀 수 있어요." else "관리자를 설정하기 전에는 켜짐으로 고정돼요.", 13f, C.DIM), top = 6)

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

        add(a.saleControls(), top = 24)

        add(a.text("Popolog ${ai.chagok.app.BuildConfig.VERSION_NAME} · 현대자동차그룹 Pleos 앱 공모전 출품용 프로토타입", 14f, C.DIM), top = 20)
    }

    private fun addDriverDialog() {
        var agreed = false
        val name = a.editText("이름", InputType.TYPE_CLASS_TEXT)
        val dept = a.editText("부서", InputType.TYPE_CLASS_TEXT, c.db.setting(AppController.KEY_DEPT))
        val body = a.vbox {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
            add(name)
            add(dept, top = 10)
            add(a.text("시트 위치는 그 사람이 운전석에 앉은 뒤 '지금 시트 위치로 등록'으로 저장합니다", 14f, C.DIM), top = 10)
            add(a.text("본인이 직접 확인하고 선택해 주세요", 16f, bold = true), top = 14)
            add(a.text(ai.chagok.app.core.PrivacyNotice.DRIVER_CONSENT, 14f, C.SUB), top = 6)
            val agree = a.choice("위 내용을 확인했고 위치·운전자 추정에 동의합니다", false) {}
            agree.setOnClickListener {
                agreed = !agreed
                agree.text = if (agreed) "✓ 위 내용을 확인했고 동의합니다" else "위 내용을 확인했고 위치·운전자 추정에 동의합니다"
            }
            add(agree, top = 10)
        }
        a.dialog("차량 이용자·운전자 추가", ScrollView(a).apply { addView(body) }, "추가") {
            val n = name.text.toString().trim()
            if (n.isNotEmpty()) c.addDriver(n, dept.text.toString().trim(), agreed)
        }
    }

    private fun consentDialog(d: ai.chagok.app.model.Driver) {
        val body = a.vbox {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
            add(a.text("${d.name}님 본인이 확인해 주세요", 16f, bold = true))
            add(a.text(ai.chagok.app.core.PrivacyNotice.DRIVER_CONSENT, 14f, C.SUB), top = 8)
        }
        a.dialog("위치·운전자 추정 동의", ScrollView(a).apply { addView(body) }, "동의합니다") {
            c.setDriverConsent(d.id, true)
            a.render()
        }
    }
}
