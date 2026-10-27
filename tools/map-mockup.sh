#!/bin/bash
# incar-capture.sh 로 찍은 1칸 창 장면마다, 옆 칸 내비의 빈 지도 면에 OpenStreetMap 지도를 깐 _mockup 사본을 만든다.
# usage: map-mockup.sh <capture-dir>
set -eu
DIR=$1; HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA="${JAVA_HOME:-$HOME/opt/jdk-17.0.21+9}/bin/java"
FONT=/mnt/c/Windows/Fonts/malgun.ttf
# 내비 앱이 잡은 현재 위치(수정구 시흥동, 판교 옆)를 창 가운데에 둔다. 줌 15
read -r Z TX TY < <(python3 -I -c 'import math;lat,lng,z=37.4127212,127.094681,15;n=2**z;print(z,(lng+180)/360*n,(1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*n)')
for f in "$DIR"/*_1col.png; do
    out="${f%.png}_mockup.png"
    printf "%s: " "$(basename "$out")"
    "$JAVA" "$HERE/MapMockup.java" "$f" "$out" "$DIR/nav_1col.xml" "$(cat "$DIR/nav_1col.bounds")" "$HERE/osm-tiles" "$Z" "$TX" "$TY" "$FONT"
done
