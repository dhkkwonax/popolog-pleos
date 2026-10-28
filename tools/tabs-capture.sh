#!/bin/bash
# 6개 탭을 2칸·1칸으로 빠르게 찍어 배치를 확인한다. usage: tabs-capture.sh <serial> <out-dir>
set -u
A="${ADB_BIN:-/mnt/c/Android/Sdk/platform-tools/adb.exe} -s $1"; export ADB="$A"; OUT=$2; U="$(dirname "$0")/ui.sh"
mkdir -p "$OUT"
read -r W H < <($A shell wm size | grep -oE "[0-9]+x[0-9]+" | tail -1 | tr x ' ')
$A shell am force-stop --user current ai.chagok.app
$A shell am start -n ai.chagok.app/.MainActivity >/dev/null; sleep 6
for mode in 2col 1col; do
  if [ $mode = 1col ]; then
    $A shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
    $A shell am start -n ai.chagok.app/.MainActivity >/dev/null; sleep 3
    $A shell input swipe $((W*1990/2000)) $((H*560/1125)) $((W*1694/2560)) $((H*560/1125)) 800; sleep 3
    $A shell input tap 1273 110; sleep 2
  fi
  i=0; for t in 홈 장부 운행일지 "세무 리포트" 장소 설정; do i=$((i+1)); $U tap "=$t" >/dev/null 2>&1; sleep 2; $A exec-out screencap -p > "$OUT/${mode}_$i.png"; done
done
