#!/bin/bash
# Pleos Connect 실제 시스템 UI(왼쪽 고정 주행 영역 포함) 위에서 차곡을 앱 창으로 띄워 전체 화면을 찍는다.
# 가로 2칸 창(앱 영역 전체)과 세로 1칸 창(손잡이로 나눈 반쪽) 두 가지를 같은 장면으로 찍는다.
# 시연 오버레이는 접은 상태로 찍고, 홈은 펼친 상태(00)와 2칸 창의 드롭다운 열린 상태(00b)도 찍는다.
# 시동 직후 운전자 인식(00a)과 운전자 바꾸기(07a 고르기, 07b 바뀐 뒤)도 찍는다.
# 1칸 창은 옆 칸 내비 화면의 UI 배치를 nav_1col.xml 로 남긴다 (map-mockup.sh 가 빈 지도 면에 지도를 깔 때 쓴다).
# usage: incar-capture.sh <serial> <out-dir>
set -u
SER=$1; OUT=$2
export ADB="$HOME/Android/Sdk/platform-tools/adb -s $SER"
A="$ADB"
U="$(dirname "$0")/ui.sh"
mkdir -p "$OUT"
die() { echo "FAILED: $*" >&2; exit 1; }
waitfor() { for _ in $(seq 1 60); do $U has "$1" && return 0; sleep 2; done; die "waitfor $1"; }
waitgone() { for _ in $(seq 1 60); do $U has "$1" || return 0; sleep 2; done; die "waitgone $1"; }
tap() { $U tap "$1" || die "tap $1"; sleep 2; }
shot() { sleep 1; $A exec-out screencap -p > "$OUT/$1.png"; echo "  $1"; }
bounds() { $A shell dumpsys activity activities | grep -A1 "A=[0-9]*:ai.chagok.app" | grep -oE "mBounds=Rect\([^)]*\)" | head -1; }

# 앱 영역 오른쪽 끝 손잡이(⋮). 끌면 앱 영역이 세로 두 칸으로 나뉜다
read -r W H < <($A shell wm size | grep -oE "[0-9]+x[0-9]+" | tail -1 | tr x ' ')
HANDLE_X=$((W * 1990 / 2000)); HANDLE_Y=$((H * 560 / 1125))

# 시연 오버레이(v2): 한 줄 패널. 도착지는 드롭다운, 나머지는 아이콘(설명으로 찾는다)
expand() { $U has "시연 컨트롤 접기" || { $U tap "시연 컨트롤 펼치기" || die "expand"; sleep 2; }; }
fold()   { if $U has "시연 컨트롤 접기"; then $U tap "시연 컨트롤 접기" >/dev/null 2>&1; sleep 1; fi; return 0; }
# 도착지를 고르면 앱이 스스로 오버레이를 접는다
drive()  { expand; $U tap "시연 도착지" || die "open dropdown"; sleep 2; $U tap "=$1" || die "pick $1"; sleep 1; }
action() { expand; $U tap "$1" || die "tap $1"; sleep 1; fold; }

scenes() {  # $1 = 파일명 접미사, $2 = 1 이면 드롭다운 열린 장면도 찍는다
    local s=$1
    expand; shot "00_home_demo_overlay_open_$s"
    if [ "${2:-0}" = 1 ]; then
        $U tap "시연 도착지" || die "open dropdown"; sleep 2; shot "00b_home_demo_dropdown_open_$s"
        $A shell input keyevent KEYCODE_BACK; sleep 1
    fi
    fold
    drive "→ 회사"; shot "01_home_driving_$s"
    fold; waitgone "● 주행"; sleep 3
    drive "→ 하남 쇼핑몰"; fold; waitgone "● 주행"; waitfor "업무였나요"; sleep 1; shot "02_ask_business_$s"
    tap "비업무"
    action "주유하기"; waitfor "감지됐어요"; sleep 1; shot "03_refuel_detected_$s"
    tap "=장부";        shot "04_ledger_$s"
    tap "=운행일지";    shot "05_logbook_$s"
    tap "=세무 리포트"; shot "06_tax_report_$s"
    # 운전자 바꾸기: 머리말의 운전자 칩을 눌러 고른다
    tap "=홈"
    local cur; cur=$(current_driver); [ -n "$cur" ] || die "no current driver"
    tap "운전자 바꾸기"; waitfor "지금 운전하는 분"; shot "07a_driver_picker_$s"
    local other; other=$($U dump | grep -oE "^(김차곡|박하나) · [^ ]+" | grep -v "^$cur " | head -1)
    [ -n "$other" ] || die "no other driver"
    tap "$other"; waitfor "님으로 바꿨어요"; shot "07b_driver_changed_$s"
}

# 홈 차량 카드의 운전자 이름 줄
current_driver() { $U dump | grep -oE "^(김차곡|박하나) \[" | head -1 | cut -d' ' -f1; }

reset_demo() {
    $A shell am force-stop --user current ai.chagok.app
    $A shell pm clear --user current ai.chagok.app >/dev/null
    $A shell pm grant --user current ai.chagok.app pleos.car.permission.FUSED_LOCATION 2>/dev/null || true
}

# 다른 반쪽에 들어갈 앱: 내비게이션 (실제 차량에서 가장 흔한 조합)
$A shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5

echo "[가로 2칸 창]"
reset_demo
$A shell am start -n ai.chagok.app/.MainActivity >/dev/null
# 시동 직후: 시트 위치로 운전자를 알아보고 잠깐 안내한다
waitfor "인식했어요"; shot "00a_ignition_driver_recognized_2col"
waitfor "시연 컨트롤"; sleep 2
echo "  창: $(bounds)"
scenes "2col" 1

# ONLY_2COL=1 이면 2칸 장면만 찍고 끝낸다
if [ "${ONLY_2COL:-0}" = 1 ]; then echo "done"; exit 0; fi

echo "[세로 1칸 창]"
reset_demo
$A shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
$A shell am start -n ai.chagok.app/.MainActivity >/dev/null
waitfor "시연 컨트롤"; sleep 2
$A shell input swipe "$HANDLE_X" "$HANDLE_Y" $((W * 1694 / 2560)) "$HANDLE_Y" 800; sleep 4
echo "  창: $(bounds)"
# 옆 칸 내비의 UI 배치를 남긴다: 내비 칸의 빈 지도 면을 눌러 포커스를 옮겨 읽고, 차곡 머리말을 눌러 되돌린다
$A shell dumpsys activity activities | grep -A1 "A=[0-9]*:ai.umos.maps" | grep -oE "mBounds=Rect\([^)]*\)" | head -1 | grep -oE "[0-9]+" | paste -sd, > "$OUT/nav_1col.bounds"
IFS=, read -r NL NT NR NB < "$OUT/nav_1col.bounds"
echo "  내비 창: $NL,$NT,$NR,$NB"
$A shell input tap $(( (NL + NR) / 2 )) $(( NT + 300 )); sleep 2
$A shell uiautomator dump /sdcard/nav.xml >/dev/null 2>&1; $A shell cat /sdcard/nav.xml > "$OUT/nav_1col.xml"
grep -q 'package="ai.umos.maps' "$OUT/nav_1col.xml" || die "nav dump"
# 차곡 머리말(제목 옆 빈 곳)을 눌러 포커스를 되돌린다
$A shell input tap $(( 853 + 420 )) 110; sleep 2
waitfor "시연 컨트롤"
# 1칸 창의 시동 장면: 박하나가 타서 시트를 맞춘다 → 시트 위치로 다시 인식
drive "박하나 탑승"; waitfor "박하나님으로 인식했어요"; shot "00a_ignition_driver_recognized_1col"
scenes "1col"
# 1칸 장면은 옆 칸 내비가 빈 지도라, 항상 지도를 깐 사본을 만든다 (Z: 에는 이 사본만 넣는다)
"$(dirname "$0")/map-mockup.sh" "$OUT"
echo "done"
