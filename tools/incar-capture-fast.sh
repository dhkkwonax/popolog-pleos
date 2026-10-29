#!/bin/bash
# incar-capture.sh 와 같은 21장(+1칸 지도 합성 10장)을 더 빨리 찍는다.
#  - 화면 읽기(uiautomator, 약 3초)는 화면이 바뀐 뒤 한 번만 하고 그 결과(UI_CACHE)로 찾기·좌표를 재사용한다
#  - 시연 운행은 촬영 때만 빨리 감는다 (am start --ei sim_speed). 틱당 거리·가상 시각은 같아 기록 결과도 같다
#  - 같은 장면을 2칸으로 찍고, 창을 나눠 1칸으로 바로 이어 찍는다 (운행·주유를 한 번만 한다)
#  - 고정 sleep 대신 조건이 맞을 때까지 기다린다
# usage: incar-capture-fast.sh <serial> <out-dir>   (Windows 에뮬레이터: emulator-5570)
set -u
SER=$1; OUT=$2
SPEED=${SIM_SPEED:-2}   # CPU 모드(화면 확인이 느림)는 SIM_SPEED=1 DOUBLE=1
HERE="$(cd "$(dirname "$0")" && pwd)"
# ADB_BIN: 기본은 Windows 에뮬레이터(Intel Arc GPU)용 Windows adb. WSL 에뮬레이터면 ADB_BIN=$HOME/Android/Sdk/platform-tools/adb
ADB_BIN=${ADB_BIN:-/mnt/c/Android/Sdk/platform-tools/adb.exe}
export ADB="$ADB_BIN -s $SER"
A="$ADB"
U="$HERE/ui.sh"
mkdir -p "$OUT"
export UI_CACHE="$OUT/.ui.xml"
T0=$(date +%s)
die() { echo "FAILED: $*" >&2; exit 1; }
log() { echo "  [$(( $(date +%s) - T0 ))s] $*"; }

# 화면이 바뀌었으면 다시 읽게 한다
stale() { rm -f "$UI_CACHE"; }
has() { $U has "$1"; }
# 조건이 맞을 때까지 다시 읽는다 (최대 약 60초)
waitfor() { for _ in $(seq 1 30); do stale; has "$1" && return 0; sleep 0.5; done; die "waitfor $1"; }
waitgone() { for _ in $(seq 1 30); do stale; has "$1" || return 0; sleep 0.5; done; die "waitgone $1"; }
# 지금 읽어 둔 화면에서 찾아 누른다. 누른 뒤에는 화면이 바뀌므로 다시 읽게 한다
tap() { $U tap "$1" || die "tap $1"; stale; }


read -r W H < <($A shell wm size | grep -oE "[0-9]+x[0-9]+" | tail -1 | tr x ' ')
HY=$((H * 560 / 1125))
SPLIT_X=$((W * 1694 / 2560))   # 1칸 창의 오른쪽 끝
HANDLE_2COL=$((W * 1990 / 2000))
HANDLE_1COL=$((W * 1705 / 2560))

chagok_right() { $A shell dumpsys activity activities | tr -d '\r' | grep -A1 "A=[0-9]*:ai.chagok.app" | grep -oE "mBounds=Rect\([^)]*\)" | head -1 | grep -oE "[0-9]+" | sed -n 3p; }
# 찍기 전에 앱 창의 실제 bounds 로 1칸/2칸을 판정해 이름의 _1col/_2col 과 맞는지 본다.
# DOUBLE=1 이면 0.7초 간격으로 두 번 찍어 픽셀이 같을 때만 저장한다 (CPU 모드용. GPU 창 모드는 지난 화면이 찍히지 않아 끈다)
shot() {
    local n=$1 want=${1##*_} r act
    local i
    for i in $(seq 1 20); do
        r=$(chagok_right); act=2col; [ "${r:-0}" -le $((SPLIT_X + 5)) ] && act=1col
        if [ "$act" != "$want" ]; then sleep 0.5; continue; fi
        # 주행 중 장면은 계속 바뀌고 운행이 짧아 한 번에 찍는다 (MOVING=1)
        if [ "${MOVING:-0}" = 1 ] || [ "${DOUBLE:-0}" != 1 ]; then $A exec-out screencap -p > "$OUT/$n.png"; log "$n"; return 0; fi
        $A exec-out screencap -p > "$OUT/.a.png"; sleep 0.7; $A exec-out screencap -p > "$OUT/.b.png"
        if [ -s "$OUT/.a.png" ] && cmp -s "$OUT/.a.png" "$OUT/.b.png"; then mv "$OUT/.b.png" "$OUT/$n.png"; rm -f "$OUT/.a.png"; log "$n"; return 0; fi
        # 주행 중처럼 계속 바뀌는 장면은 창 판정이 맞으면 여섯 번째에 받아들인다
        if [ "$i" -ge 6 ] && [ -s "$OUT/.b.png" ]; then mv "$OUT/.b.png" "$OUT/$n.png"; rm -f "$OUT/.a.png"; log "$n (화면 변화 중)"; return 0; fi
    done
    die "shot $n (창 $act, 기대 $want)"
}
focus_chagok() { $A shell input tap $((W * 893 / 2560)) $((H * 110 / 1440)); }
MODE=2col
# 손잡이를 끌어 창을 나누거나 합친다. 창 크기가 바뀔 때까지 기다리고 차곡에 초점을 둔다
toggle() {
    if [ $MODE = 2col ]; then
        $A shell input swipe "$HANDLE_2COL" "$HY" "$SPLIT_X" "$HY" 500; MODE=1col; want=$SPLIT_X
    else
        $A shell input swipe "$HANDLE_1COL" "$HY" "$((W - 10))" "$HY" 500; MODE=2col; want=0
    fi
    ok=0
    for _ in $(seq 1 40); do
        r=$(chagok_right)
        if [ $want = 0 ] && [ "${r:-0}" -gt $((SPLIT_X + 100)) ]; then ok=1; break; fi
        if [ $want != 0 ] && [ "${r:-0}" -le $((SPLIT_X + 5)) ] && [ "${r:-0}" -gt 0 ]; then ok=1; break; fi
        sleep 0.25
    done
    [ $ok = 1 ] || die "창 전환 실패 ($MODE)"
    # 차곡 창 왼쪽 위(누를 것이 없는 자리)를 눌러 초점을 옮긴다. 가운데를 누르면 펼친 시연 오버레이가 열린다
    focus_chagok
    sleep 0.6; stale
}
# 지금 창에서 한 장, 창을 바꿔 한 장 (파일 이름은 각 창 모드로)
both() { shot "${1}_$MODE"; toggle; shot "${1}_$MODE"; }

# 시연 오버레이
expand() { has "시연 컨트롤 접기" || { tap "시연 컨트롤 펼치기"; waitfor "시연 컨트롤 접기"; }; }
fold()   { has "시연 컨트롤 접기" && { tap "시연 컨트롤 접기"; sleep 0.5; }; return 0; }
drive()  { expand; tap "시연 도착지"; waitfor "$1"; tap "=$1"; }
current_driver() { $U dump | grep -oE "(김도윤|박하나) \[" | head -1 | cut -d' ' -f1; }

echo "[준비] 빨리 감기 ×$SPEED, 주간 모드"
# 기본 21장은 주간. Pleos 시스템 UI 와 앱이 함께 시스템 야간 모드를 따른다
$A shell cmd uimode night no >/dev/null
$A shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
$A shell am force-stop --user current ai.chagok.app
$A shell pm clear --user current ai.chagok.app >/dev/null
$A shell pm grant --user current ai.chagok.app pleos.car.permission.FUSED_LOCATION 2>/dev/null || true
sleep 2
$A shell am start -n ai.chagok.app/.MainActivity --ei sim_speed "$SPEED" >/dev/null
stale

# 00a 시동 직후 운전자 인식 (안내는 12초 동안 보인다)
waitfor "인식했어요"; both 00a_ignition_driver_recognized
# 1칸일 때 옆 칸 내비 배치를 한 번 남긴다 (지도 합성용)
$A shell dumpsys activity activities | grep -A1 "A=[0-9]*:ai.umos.maps" | grep -oE "mBounds=Rect\([^)]*\)" | head -1 | grep -oE "[0-9]+" | paste -sd, > "$OUT/nav_1col.bounds"
IFS=, read -r NL NT NR NB < "$OUT/nav_1col.bounds"
$A shell input tap $(( (NL + NR) / 2 )) $(( NT + 300 )); sleep 0.8
$A shell uiautomator dump /sdcard/nav.xml >/dev/null 2>&1; $A shell cat /sdcard/nav.xml > "$OUT/nav_1col.xml"
grep -q 'package="ai.umos.maps' "$OUT/nav_1col.xml" || die "nav dump"
focus_chagok; sleep 0.6; stale
log "내비 배치 저장"
# 인사 카드(\"…님으로 인식했어요\")가 사라질 때까지. 운전자 확인 질문(\"…인식했어요. 맞으세요?\")은 따로 남아 있다
waitgone '님으로 인식했어요"'
# 앱이 먼저 묻는 운전자 확인 질문에 답해 둔다 (답하지 않으면 뒤의 질문이 줄에서 기다린다)
stale; tap "=맞아요"; sleep 0.8; stale

# 00 오버레이 펼침 (지금 1칸) → 2칸
expand; both 00_home_demo_overlay_open
# 00b 드롭다운 열림 (2칸만)
stale; expand; tap "시연 도착지"; waitfor "→ 회사"; shot 00b_home_demo_dropdown_open_2col
$A shell input keyevent KEYCODE_BACK; sleep 0.3; stale; fold

# 01 주행 중 (2칸 → 1칸, 같은 운행)
# 운행 중에는 화면이 계속 바뀌어 화면 읽기(uiautomator)가 멈춰 기다리므로, 앱이 남기는 logcat 으로 운행 시작·끝을 본다.
# 시작을 본 뒤 2칸·1칸을 찍고, 그때까지 운행이 끝나지 않았는지 확인한다
triplog() { $A logcat -d -s Popolog:I | tr -d '\r' | grep -c "trip $1"; }
$A logcat -c
drive "→ 회사"
for _ in $(seq 1 40); do [ "$(triplog started)" -ge 1 ] && break; sleep 0.25; done
[ "$(triplog started)" -ge 1 ] || die "운행 시작이 안 보임"
sleep 0.5; MOVING=1 shot "01_home_driving_$MODE"; toggle; MOVING=1 shot "01_home_driving_$MODE"
[ "$(triplog ended)" = 0 ] || die "운행 장면을 찍는 동안 운행이 끝남 (SIM_SPEED=1 로 다시)"
waitgone "주행 중"
# 02 미등록 장소에 도착: 먼저 저장할지 묻고, 저장하지 않으면 운행 목적을 묻는다 (1칸 → 2칸)
stale; drive "→ 하남 쇼핑몰"; waitfor "저장할까요"; both 02a_place_save
stale; tap "=저장 안 함"; waitfor "업무 운행이었나요"; both 02_ask_business
stale; tap "=비업무"
# 03 주유 감지 → 금액을 묻고(말하기 시연 응답) 확인한다 (1칸 → 2칸)
stale; expand; tap "주유하기"; waitfor "감지됐어요"; fold; stale; has "시연 컨트롤 접기" && fold
both 03_refuel_detected
stale; tap "=말하기"; waitfor "말한 내용을 입력"; tap "말한 내용을 입력"; sleep 0.4; $A shell input text "73000"; sleep 0.4; $A shell input keyevent 66; sleep 0.5; stale
both 03b_amount_speech_input
# 창 모드를 바꾸면 앱이 뒤로 갈 수 있어 다시 앞으로 가져온다
$A shell monkey -p ai.chagok.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 1
waitfor "확인"; tap "=확인"; waitfor "맞나요"; both 03c_amount_confirm
stale; tap "=맞아요"; sleep 1; stale

# 04–06 탭 (탭 위치는 창 모드마다 다르니 매번 읽은 화면에서 찾는다)
for pair in "=장부:04_ledger" "=운행일지:05_logbook" "=세무 리포트:06_tax_report"; do
    t=${pair%%:*}; n=${pair#*:}
    stale; tap "$t"; sleep 0.6; shot "${n}_$MODE"
    toggle; stale; tap "$t"; sleep 0.6; shot "${n}_$MODE"
done

# 07a 운전자 고르기 / 07b 바뀐 뒤
stale; tap "=홈"; sleep 0.6; stale
cur=$(current_driver); [ -n "$cur" ] || die "no current driver"
waitfor "운전자 바꾸기"; tap "운전자 바꾸기"; waitfor "지금 운전하는 분"; shot "07a_driver_picker_$MODE"
$A shell input keyevent KEYCODE_BACK; sleep 0.3; toggle
waitfor "운전자 바꾸기"; tap "운전자 바꾸기"; waitfor "지금 운전하는 분"; shot "07a_driver_picker_$MODE"
other=$($U dump | grep -oE "^(김도윤|박하나) · [^ ]+" | grep -v "^$cur " | head -1)
[ -n "$other" ] || die "no other driver"
tap "$other"; waitfor "님으로 바꿨어요"; both 07b_driver_changed

# 09 위변조 검증 (세무 리포트의 '기록 무결성' 카드)
# 시작할 때 받은 RFC 3161 타임스탬프(freetsa.org)가 카드에 보일 때까지 (네트워크 필요)
for _ in 1 2 3; do stale; tap "=세무 리포트"; sleep 1; stale; has "기록 무결성" && break; done
waitfor "정상"; waitfor "서명 확인"; both 09a_integrity_ok
# 09b 시연 오버레이 '기록 위조해 보기' → 몇 번째 기록부터 불일치인지 빨간색으로
stale; expand; tap "기록 위조해 보기"; sleep 1; stale; fold; stale; has "불일치" || die "위조 탐지 안 됨"
both 09b_tamper_detected
stale; expand; tap "위조 되돌리기"; sleep 1; stale; fold; stale; has "정상" || die "되돌리기 실패"
# 09c 운행기록부 PDF 미리보기 (검증 코드·QR) → 09d 검증 페이지 (QR 이 여는 페이지, 앱 안에서 체인 재계산)
pdf_and_verify() {
    stale; tap "운행기록부 PDF"; waitfor "검증 페이지"; sleep 0.8; shot "09c_pdf_preview_$MODE"
    tap "=검증 페이지"; waitfor "모두 다시 계산해 일치"; sleep 0.5; shot "09d_verify_page_$MODE"
    $A shell input keyevent KEYCODE_BACK; sleep 0.4; $A shell input keyevent KEYCODE_BACK; sleep 0.5
}
pdf_and_verify; toggle; pdf_and_verify
stale; tap "=홈"; sleep 0.6

# 10 앱을 끈 채 달린 거리: 계기판만 늘어난 채로 다시 시동 → 보정 운행 + 목적 질문
stale; expand; tap "앱 끈 채 운행 (12km)"; waitfor "앱이 꺼진 동안"; fold; stale; has "시연 컨트롤 접기" && fold
both 10_offline_gap
# 다시 시동하면 운전자 확인 질문이 먼저 나온다
stale; has "맞으세요" && tap "=맞아요"; sleep 0.8; stale; waitfor "비업무"; tap "=비업무"; sleep 0.8

# 11 설정의 화면 테마 (자동·주간·야간)
stale; tap "=설정"; sleep 0.6; shot "11_settings_theme_$MODE"
toggle; stale; tap "=설정"; sleep 0.6; shot "11_settings_theme_$MODE"
stale; tap "=홈"; sleep 0.6

# 12 도착한 곳 저장 대화: 저장 → 종류 → 이름 후보(내비 즐겨찾기) → 저장하면 목적이 자동 분류된다
stale; has "맞으세요" && { tap "=맞아요"; sleep 0.8; }
stale; drive "→ 미사 휴게소"; waitfor "저장할까요"; both 12a_place_save
stale; tap "=저장"; waitfor "무엇으로 저장"; both 12b_place_kind
stale; tap "=거래처"; waitfor "이름을 말씀"; both 12c_place_name
stale; tap "=미사 휴게소"; sleep 1.5; stale; waitgone "이름을 말씀"
both 12d_place_saved

# 13 비용 입력(터치): 종류·항목 칩, 결제한 곳, 숫자패드 → 확인 카드 → 저장
# 창 폭은 대화상자를 열 때 정해지므로 칸을 바꾼 뒤에는 닫았다가 다시 열어 찍는다
fill13() {
    stale; tap "=장부"; sleep 0.6; stale; tap "+ 비용 추가"; waitfor "비용 입력"
    tap "=엔진오일"; tap "=1"; tap "=2"; tap "=00"; tap "=00"
    tap "정비소·주유소·주차장 (선택)"; sleep 0.4; $A shell input text "SpeedMate"; sleep 0.4; $A shell input keyevent 66; sleep 0.6; stale
}
fill13; shot "13a_expense_input_$MODE"; toggle
$A shell input keyevent KEYCODE_BACK; sleep 0.6
$A shell monkey -p ai.chagok.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 1
fill13; shot "13a_expense_input_$MODE"
waitfor "확인"; tap "=확인"; waitfor "이 내용으로 저장할까요"; shot "13b_expense_confirm_$MODE"; toggle; shot "13b_expense_confirm_$MODE"
stale; tap "=저장"; sleep 1; stale; both 13c_ledger_saved

# 14 Gleo 음성 명령 (시연: Gleo 가 보내는 것과 같은 호출을 앱에 보낸다) → 확인 카드, 기간별 조회
stale; expand; tap "Gleo 음성 명령 시연"; waitfor "정비 12만 원"; fold; both 14a_gleo_samples
stale; tap "정비 12만 원"; waitfor "이 내용으로 저장할까요"; both 14b_gleo_confirm
stale; tap "=저장"; sleep 1; stale
stale; expand; tap "Gleo 음성 명령 시연"; waitfor "정비비 알려"; tap "정비비 알려"; waitfor "이번 달 정비 비용"; both 14c_gleo_report
stale; tap "=닫기"; sleep 0.6; stale

# 15 외부 연동 내역 검토 (시연: 샘플 3건 → 승인·거절)
stale; expand; tap "연동 내역 가져오기"; waitfor "연동 내역 3건"; fold; both 15_sync_inbox

# 16 정비 시기 알림 (주행거리 기준 교체 주기)
stale; expand; tap "정비 시기 임박"; fold; stale; tap "=홈"; waitfor "교체 시기"; both 16_maint_due
stale; has "정비하셨나요" && tap "=나중에"; sleep 0.8; stale

# 17 설정: 음성 점검·정비 주기 카드
settings_scroll() { stale; tap "=설정"; sleep 0.6; $A shell input swipe $((W * 7 / 10)) $((H * 8 / 10)) $((W * 7 / 10)) $((H * 3 / 10)) 400; sleep 0.8; }
settings_scroll; shot "17_settings_voice_maintenance_$MODE"
$A shell input swipe $((W * 7 / 10)) $((H * 3 / 10)) $((W * 7 / 10)) $((H * 8 / 10)) 300
toggle; settings_scroll; shot "17_settings_voice_maintenance_$MODE"
$A shell input swipe $((W * 7 / 10)) $((H * 3 / 10)) $((W * 7 / 10)) $((H * 8 / 10)) 300
stale; tap "=홈"; sleep 0.6

# 2칸으로 되돌려 둔다
[ $MODE = 1col ] && toggle

# 야간 모드 장면은 incar-capture-night.sh (GPU 에뮬레이터는 켜진 화면에서 야간을 바꾸면 screencap 이 깨진다)
rm -f "$UI_CACHE"
log "촬영 끝"
"$HERE/map-mockup.sh" "$OUT" >/dev/null
log "지도 합성 끝"
ls "$OUT"/*.png | wc -l
echo "done"
