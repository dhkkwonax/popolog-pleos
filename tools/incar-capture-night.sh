#!/bin/bash
# 야간 모드 홈 2장(2칸·1칸)을 찍는다.
# GPU(Windows) 에뮬레이터는 앱이 떠 있는 동안 야간을 바꾸면 screencap 이 깨진 화면을 돌려준다.
# 그래서 에뮬레이터를 막 켠 뒤(앱을 띄우기 전) 야간으로 바꾸고 찍는다. 야간 설정은 재부팅하면 주간으로 돌아간다.
# usage: incar-capture-night.sh <serial> <out-dir> [<day-capture-dir>]   (에뮬레이터를 갓 켠 상태에서)
#   day-capture-dir 를 주면 그 촬영의 1칸 기준 장면으로 내비 칸을 맞춰 지도 합성본도 만든다
set -u
SER=$1; OUT=$2; DAY=${3:-}
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB_BIN=${ADB_BIN:-/mnt/c/Android/Sdk/platform-tools/adb.exe}
export ADB="$ADB_BIN -s $SER"
A="$ADB"
U="$HERE/ui.sh"
mkdir -p "$OUT"
export UI_CACHE="$OUT/.ui.xml"
T0=$(date +%s)
die() { echo "FAILED: $*" >&2; exit 1; }
log() { echo "  [$(( $(date +%s) - T0 ))s] $*"; }
stale() { rm -f "$UI_CACHE"; }
has() { $U has "$1"; }
waitfor() { for _ in $(seq 1 30); do stale; has "$1" && return 0; sleep 0.5; done; die "waitfor $1"; }
waitgone() { for _ in $(seq 1 30); do stale; has "$1" || return 0; sleep 0.5; done; die "waitgone $1"; }
tap() { $U tap "$1" || die "tap $1"; stale; }


read -r W H < <($A shell wm size | grep -oE "[0-9]+x[0-9]+" | tail -1 | tr x ' ')
HY=$((H * 560 / 1125))
SPLIT_X=$((W * 1694 / 2560))
HANDLE_2COL=$((W * 1990 / 2000))
chagok_right() { $A shell dumpsys activity activities | tr -d '\r' | grep -A1 "A=[0-9]*:ai.chagok.app" | grep -oE "mBounds=Rect\([^)]*\)" | head -1 | grep -oE "[0-9]+" | sed -n 3p; }
# 찍기 전에 앱 창의 실제 bounds 로 1칸/2칸을 판정해 이름의 _1col/_2col 과 맞는지 본다.
# DOUBLE=1 이면 0.7초 간격으로 두 번 찍어 픽셀이 같을 때만 저장한다 (CPU 모드용. GPU 창 모드는 지난 화면이 찍히지 않아 끈다)
shot() {
    local n=$1 want=${1##*_} r act
    local i
    for i in $(seq 1 20); do
        r=$(chagok_right); act=2col; [ "${r:-0}" -le $((SPLIT_X + 5)) ] && act=1col
        if [ "$act" != "$want" ]; then sleep 0.5; continue; fi
        if [ "${DOUBLE:-0}" != 1 ]; then $A exec-out screencap -p > "$OUT/$n.png"; log "$n"; return 0; fi
        $A exec-out screencap -p > "$OUT/.a.png"; sleep 0.7; $A exec-out screencap -p > "$OUT/.b.png"
        if [ -s "$OUT/.a.png" ] && cmp -s "$OUT/.a.png" "$OUT/.b.png"; then mv "$OUT/.b.png" "$OUT/$n.png"; rm -f "$OUT/.a.png"; log "$n"; return 0; fi
        # 주행 중처럼 계속 바뀌는 장면은 창 판정이 맞으면 여섯 번째에 받아들인다
        if [ "$i" -ge 6 ] && [ -s "$OUT/.b.png" ]; then mv "$OUT/.b.png" "$OUT/$n.png"; rm -f "$OUT/.a.png"; log "$n (화면 변화 중)"; return 0; fi
    done
    die "shot $n (창 $act, 기대 $want)"
}
focus_chagok() { $A shell input tap $((W * 893 / 2560)) $((H * 110 / 1440)); }

$A shell cmd uimode night yes >/dev/null; sleep 6
$A shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
$A shell am force-stop --user current ai.chagok.app
$A shell pm clear --user current ai.chagok.app >/dev/null
$A shell pm grant --user current ai.chagok.app pleos.car.permission.FUSED_LOCATION 2>/dev/null || true
sleep 2
$A shell am start -n ai.chagok.app/.MainActivity --ei sim_speed 2 >/dev/null
stale
waitfor "인식했어요"; waitgone "인식했어요"
# 홈에 조치 필요 카드(앰버)가 보이도록 주유 한 번
tap "시연 컨트롤 펼치기"; waitfor "시연 컨트롤 접기"; tap "주유하기"; waitfor "감지됐어요"
stale; has "시연 컨트롤 접기" && tap "시연 컨트롤 접기"
sleep 1; shot 08_home_night_2col
$A shell input swipe "$HANDLE_2COL" "$HY" "$SPLIT_X" "$HY" 500
for _ in $(seq 1 40); do r=$(chagok_right); [ "${r:-0}" -gt 0 ] && [ "$r" -le $((SPLIT_X + 5)) ] && break; sleep 0.25; done
[ "${r:-0}" -le $((SPLIT_X + 5)) ] || die "창 나누기 실패"
focus_chagok; sleep 1.5; shot 08_home_night_1col
rm -f "$UI_CACHE"
if [ -n "$DAY" ]; then
    cp "$DAY/nav_1col.bounds" "$DAY/nav_1col.xml" "$OUT/"
    "$HERE/map-mockup.sh" "$OUT" >/dev/null
    IFS=, read -r NL NT NR NB < "$OUT/nav_1col.bounds"
    "${JAVA_HOME:-$HOME/opt/jdk-17.0.21+9}/bin/java" "$HERE/NavPanePaste.java" "$DAY/00a_ignition_driver_recognized_1col_mockup.png" "$OUT/08_home_night_1col.png" "$OUT/08_home_night_1col_mockup.png" "$NL,$NT,$NR,$NB"
fi
log "끝"
