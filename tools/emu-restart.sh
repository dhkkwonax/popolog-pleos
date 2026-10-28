#!/bin/bash
# Windows 에뮬레이터를 (다시) 켜고 부팅이 끝날 때까지 기다린다.
# usage: emu-restart.sh [cpu|gpu]   (기본 cpu: 촬영 검증 완료. gpu 는 Windows 화면에 창을 띄운다.
#   GPU 를 창 없이(-no-window) 돌리면 screencap 이 2칸·야간 장면을 틀리게 찍는다)
MODE=${1:-cpu}
WA=/mnt/c/Android/Sdk/platform-tools/adb.exe; S=emulator-5570
if [ "$MODE" = gpu ]; then G="-gpu host -feature -Vulkan"; else G="-no-window -gpu swiftshader_indirect"; fi
timeout 20 $WA -s $S emu kill >/dev/null 2>&1; sleep 10
timeout 60 powershell.exe -NoProfile -Command '$env:ANDROID_SDK_ROOT="C:\Android\Sdk"; $env:ANDROID_AVD_HOME="C:\Android\avd"; $env:ANDROID_EMULATOR_HOME="C:\Android\home"; Start-Process -FilePath C:\Android\Sdk\emulator\emulator.exe -ArgumentList "-avd pleos17 -no-audio -no-snapshot '"$G"' -memory 4096 -cores 6 -port 5570" -RedirectStandardOutput C:\Android\emu.log -RedirectStandardError C:\Android\emu.err -WindowStyle Hidden' >/dev/null 2>&1
for _ in $(seq 1 100); do [ "$(timeout 10 $WA -s $S shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { echo "booted ($MODE)"; break; }; sleep 3; done
# 시스템 UI·내비가 자리 잡을 때까지
sleep 25
