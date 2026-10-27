#!/bin/bash
# usage: ui.sh tap "텍스트" | ui.sh where "텍스트" | ui.sh has "텍스트" | ui.sh shot path | ui.sh dump
# DISP=<id> 를 주면 그 디스플레이(예: 폼팩터 테스트용 가상 디스플레이)에 탭·스크롤·촬영한다
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
IN="input"; [ -n "${DISP:-}" ] && IN="input -d $DISP"
# UI_CACHE=<파일> 을 주면 화면 읽기(약 3초)를 한 번만 하고 그 파일을 다시 쓴다.
# 화면이 바뀐 뒤에는 부르는 쪽이 파일을 지워 다시 읽게 한다.
dump() {
  if [ -n "${UI_CACHE:-}" ] && [ -s "$UI_CACHE" ]; then cat "$UI_CACHE"; return; fi
  if [ -n "${UI_CACHE:-}" ]; then
    $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $A shell cat /sdcard/ui.xml > "$UI_CACHE"; cat "$UI_CACHE"
  else
    $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $A shell cat /sdcard/ui.xml
  fi
}
case "$1" in
  dump) dump | python3 -I -c 'import sys,re; [print(t,b) for t,b in re.findall(r"text=\"([^\"]+)\"[^>]*bounds=\"([^\"]+)\"", sys.stdin.read())]' ;;
  has) dump | grep -q -- "$2" ;;
  tap|where) b=$(dump | python3 -I -c '
import sys, re, xml.etree.ElementTree as ET
t = sys.argv[1]
# "=텍스트" 는 정확히 일치하는 노드만 고른다 (본문 문장 속 같은 단어를 잘못 누르지 않게)
exact = t.startswith("=")
if exact: t = t[1:]
root = ET.fromstring(sys.stdin.read())
parent = {c: p for p in root.iter() for c in p}
def box(n):
    return list(map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))))
for n in root.iter("node"):
    txt, desc = n.get("text", ""), n.get("content-desc", "")
    if (exact and t not in (txt, desc)) or (not exact and t not in txt and t not in desc):
        continue
    a = box(n)
    if a[2] > a[0]:
        print((a[0]+a[2])//2, (a[1]+a[3])//2); break
    # uiautomator 는 기본 디스플레이 폭(1920) 밖을 [0,0][0,0] 으로 잘라낸다.
    # 같은 줄에 같은 폭 버튼이 이어지는 경우, 앞 두 형제의 간격으로 위치를 이어서 계산한다.
    sib = list(parent[n]); i = sib.index(n)
    prev = [box(x) for x in sib[:i] if box(x)[2] > box(x)[0]]
    if len(prev) >= 2:
        p1, p2 = prev[-2], prev[-1]
        step = p2[0] - p1[0]; w = p1[2] - p1[0]
        x0 = p2[0] + step
        print(x0 + w//2, (p2[1]+p2[3])//2)
    break
' "$2"); [ -n "$b" ] || { echo "not found: $2" >&2; exit 1; }
    # where: 좌표만 출력 (여러 번 빠르게 누를 때 dump 를 한 번만 하려고)
    if [ "$1" = where ]; then echo "$b"; else $A shell $IN tap $b; fi ;;
  shot)
    if [ -n "${DISP:-}" ]; then
      # 가상 디스플레이는 screencap 으로 안 잡혀 에뮬레이터 콘솔로 찍는다
      d=$(mktemp -d); $A emu screenrecord screenshot "$d" --display "$DISP" >/dev/null
      for _ in 1 2 3 4 5 6 7 8 9 10; do f=$(ls "$d"/*.png 2>/dev/null | head -1); [ -n "$f" ] && break; sleep 0.5; done
      sleep 0.5; mv "$f" "$2"; rmdir "$d"
    else
      $A exec-out screencap -p > "$2"
    fi ;;
  scroll) $A shell $IN swipe ${3:-1300} ${4:-1300} ${3:-1300} ${5:-400} 400 ;;
esac
