package ai.chagok.app.ui

import ai.chagok.app.AppController
import ai.chagok.app.MainActivity
import android.text.InputType
import android.view.View

class SettingsScreen(private val a: MainActivity) {
    private val c = a.controller

    fun render(): View = a.vbox {
        val company = a.editText("법인·상호명", InputType.TYPE_CLASS_TEXT, c.db.setting(AppController.KEY_COMPANY))
        val bizNo = a.editText("사업자등록번호", InputType.TYPE_CLASS_TEXT, c.db.setting(AppController.KEY_BIZNO))

        add(a.card {
            add(a.text("운행기록부 머리말", 20f, bold = true))
            add(a.text("국세청 서식의 법인명·사업자번호 칸에 들어갑니다. 부서·성명은 운전자별로 들어갑니다", 15f, C.SUB), top = 6)
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
            add(a.text("운전자", 20f, bold = true))
            add(a.text("시동 직후 운전석 시트 위치 → 평소 출발 시간·장소 → 마지막 운전자 순서로 알아봅니다", 15f, C.SUB), top = 6)
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
            add(line("데이터 출처", if (c.gateway.isSimulated) "시뮬레이터 (Pleos SDK 미연결 기기)" else "Pleos Vehicle · FusedLocation SDK"), top = 10)
            add(line("사용하는 SDK", "Odometer · Fuel/EvBattery · Brake · CarInfo · FusedLocation · STT · TTS"), top = 10)
        }, top = 16)

        add(a.text("Popolog 0.1.0 데모 · 현대자동차그룹 Pleos 앱 공모전 출품용 프로토타입", 14f, C.DIM), top = 20)
    }
}
