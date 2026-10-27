#!/bin/bash
# incar-capture.sh 와 같은 21장(+1칸 지도 합성 10장)을 더 빨리 찍는다.
#  - 화면 읽기(uiautomator, 약 3초)는 화면이 바뀐 뒤 한 번만 하고 그 결과(UI_CACHE)로 찾기·좌표를 재사용한다
#  - 시연 운행은 촬영 때만 빨리 감는다 (am start --ei sim_speed). 틱당 거리·가상 시각은 같아 기록 결과도 같다
#  - 같은 장면을 2칸으로 찍고, 창을 나눠 1칸으로 바로 이어 찍는다 (운행·주유를 한 번만 한다)
#  - 고정 sleep 대신 조건이 맞을 때까지 기다린다
# usage: incar-capture-fast.sh <serial> <out-dir>
set -u
SER=$1; OUT=$2
SPEED=${SIM_SPEED:-2}
HERE="$(cd "$(dirname "$0")" && pwd)"
export ADB="$HOME/Android/Sdk/platform-tools/adb -s $SER"
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
shot() { $A exec-out screencap -p > "$OUT/$1.png"; log "$1"; }

read -r W H < <($A shell wm size | grep -oE "[0-9]+x[0-9]+" | tail -1 | tr x ' ')
HY=$((H * 560 / 1125))
SPLIT_X=$((W * 1694 / 2560))   # 1칸 창의 오른쪽 끝
HANDLE_2COL=$((W * 1990 / 2000))
HANDLE_1COL=$((W * 1705 / 2560))

chagok_right() { $A shell dumpsys activity activities | grep -A1 "A=[0-9]*:ai.chagok.app" | grep -oE "mBounds=Rect\([^)]*\)" | head -1 | grep -oE "[0-9]+" | sed -n 3p; }
focus_chagok() { $A shell input tap $((W * 893 / 2560)) $((H * 110 / 1440)); }
MODE=2col
# 손잡이를 끌어 창을 나누거나 합친다. 창 크기가 바뀔 때까지 기다리고 차곡에 초점을 둔다
toggle() {
    if [ $MODE = 2col ]; then
        $A shell input swipe "$HANDLE_2COL" "$HY" "$SPLIT_X" "$HY" 500; MODE=1col; want=$SPLIT_X
    else
        $A shell input swipe "$HANDLE_1COL" "$HY" "$((W - 10))" "$HY" 500; MODE=2col; want=0
    fi
    for _ in $(seq 1 40); do
        r=$(chagok_right)
        if [ $want = 0 ] && [ "${r:-0}" -gt $((SPLIT_X + 100)) ]; then break; fi
        if [ $want != 0 ] && [ "${r:-0}" -le $((SPLIT_X + 5)) ] && [ "${r:-0}" -gt 0 ]; then break; fi
        sleep 0.25
    done
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
current_driver() { $U dump | grep -oE "(김차곡|박하나) \[" | head -1 | cut -d' ' -f1; }

echo "[준비] 빨리 감기 ×$SPEED"
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
waitgone "인식했어요"

# 00 오버레이 펼침 (지금 1칸) → 2칸
expand; both 00_home_demo_overlay_open
# 00b 드롭다운 열림 (2칸만)
stale; expand; tap "시연 도착지"; waitfor "→ 회사"; shot 00b_home_demo_dropdown_open_2col
$A shell input keyevent KEYCODE_BACK; sleep 0.3; stale; fold

# 01 주행 중 (2칸 → 1칸, 같은 운행)
drive "→ 회사"; sleep 0.8; both 01_home_driving
waitgone "주행 중"
# 02 업무였나요 (1칸 → 2칸)
stale; drive "→ 하남 쇼핑몰"; waitfor "업무였나요"; both 02_ask_business
stale; tap "=비업무"
# 03 주유 감지 (1칸 → 2칸)
stale; expand; tap "주유하기"; waitfor "감지됐어요"; fold; stale; has "시연 컨트롤 접기" && fold
both 03_refuel_detected

# 04–06 탭 (탭 위치는 창 모드마다 다르니 매번 읽은 화면에서 찾는다)
for pair in "=장부:04_ledger" "=운행일지:05_logbook" "=세무 리포트:06_tax_report"; do
    t=${pair%%:*}; n=${pair#*:}
    stale; tap "$t"; sleep 0.6; shot "${n}_$MODE"
    toggle; stale; tap "$t"; sleep 0.6; shot "${n}_$MODE"
done

# 07a 운전자 고르기 / 07b 바뀐 뒤
stale; tap "=홈"; sleep 0.6; stale
cur=$(current_driver); [ -n "$cur" ] || die "no current driver"
tap "운전자 바꾸기"; waitfor "지금 운전하는 분"; shot "07a_driver_picker_$MODE"
$A shell input keyevent KEYCODE_BACK; sleep 0.3; toggle
tap "운전자 바꾸기"; waitfor "지금 운전하는 분"; shot "07a_driver_picker_$MODE"
other=$($U dump | grep -oE "^(김차곡|박하나) · [^ ]+" | grep -v "^$cur " | head -1)
[ -n "$other" ] || die "no other driver"
tap "$other"; waitfor "님으로 바꿨어요"; both 07b_driver_changed

# 2칸으로 되돌려 둔다
[ $MODE = 1col ] && toggle
rm -f "$UI_CACHE"
log "촬영 끝"
"$HERE/map-mockup.sh" "$OUT" >/dev/null
log "지도 합성 끝"
ls "$OUT"/*.png | wc -l
echo "done"
