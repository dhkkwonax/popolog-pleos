# Pleos Connect 에뮬레이터에서 Popolog 실행·촬영하기 (미니PC)

에뮬레이터는 **Windows 쪽**에서 돌리고, 빌드와 촬영 스크립트는 **WSL** 에서 돌린다.
**촬영 기본값은 GPU 창 모드(`./emu-restart.sh gpu`, Windows 화면에 에뮬레이터 창을 띄움)다.** GPU 는 창을 띄울 때만 캡처가 맞다.
대안은 CPU 렌더링(`./emu-restart.sh cpu`, 창 없음, 약 40초 느림) (아래 '촬영').
(WSL 안 에뮬레이터는 GPU 를 쓰지 못해 CPU 렌더링이었고 지웠다. 이유는 아래 '참고'.)

## 설치된 것

| 위치 | 내용 |
|---|---|
| `C:\Android\Sdk` | Windows 에뮬레이터 37.2.12, platform-tools(adb), Pleos Connect v2.1.0 시스템 이미지 |
| `C:\Android\avd\pleos17.avd` | 공식 17″ Connect-S 프리셋 (2560×1440, 160dpi, RAM 4GB, 6코어, `hw.gpu.mode=host`) |
| `C:\Android\home` | adb 키 (WSL `~/.android/adbkey` 복사본). Windows 사용자 폴더가 한글이라 영문 경로에 둔다 |
| WSL `~/opt/jdk-17.0.21+9`, `~/opt/gradle-8.10.2`, `~/Android/Sdk` | 빌드용 JDK·Gradle·SDK(platforms, build-tools) |
| WSL `~/Downloads/pleos/crp_car_emulator.zip` | Pleos 이미지 원본 (재설치용, 4GB) |

경로는 영문만 쓴다. 관리자 권한은 필요 없다 (WHPX 가속은 이미 켜져 있음).

## 에뮬레이터 띄우기 (WSL 에서)

```bash
powershell.exe -NoProfile -Command '$env:ANDROID_SDK_ROOT="C:\Android\Sdk"; $env:ANDROID_AVD_HOME="C:\Android\avd"; $env:ANDROID_EMULATOR_HOME="C:\Android\home"; Start-Process -FilePath C:\Android\Sdk\emulator\emulator.exe -ArgumentList "-avd pleos17 -no-window -no-audio -no-snapshot -gpu host -feature -Vulkan -memory 4096 -cores 6 -port 5570" -RedirectStandardOutput C:\Android\emu.log -RedirectStandardError C:\Android\emu.err -WindowStyle Hidden'
WA=/mnt/c/Android/Sdk/platform-tools/adb.exe
until [ "$($WA -s emulator-5570 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
```

- 위 명령은 창 없는 GPU 모드라 **촬영에는 쓰지 않는다** (2칸·야간 캡처가 틀린다). 촬영은 `tools/emu-restart.sh gpu`(창 띄움) 또는 `cpu`
- `-gpu host` : Intel Arc 140T 로 그린다 (`emu.log` 에 `Graphics Adapter … Intel(R) Arc(TM) 140T GPU`)
- `-feature -Vulkan` : **꼭 필요.** 켜 두면 왼쪽 Pleos 차량 그래픽이 질감 없이 하얀 모형으로 나온다
- `-gpu angle_indirect` 는 GPU 가 아니라 SwiftShader(CPU)로 떨어지므로 쓰지 않는다
- 끄기: `$WA -s emulator-5570 emu kill`

## 빌드·설치 (두 가지 모드)

빌드 옵션(product flavor)으로 모드를 고른다. 두 APK 는 패키지 이름이 같아(`ai.chagok.app`) 하나만 깔린다.

| 모드 | 빌드 | 동작 |
|---|---|---|
| 데모 모드 `demo` | `./gradlew assembleDemoDebug` → `app/build/outputs/apk/demo/debug/app-demo-debug.apk` | 가상 차량·가상 시계(주행 중 빨리 흐름)·시연 오버레이·6월 이후 예시 데이터 |
| 실제 동작 모드 `real` | `./gradlew assembleRealDebug` → `app/build/outputs/apk/real/debug/app-real-debug.apk` | Pleos Vehicle·FusedLocation SDK 값만 쓴다. 예시 데이터·시연 컨트롤 없음, 운전자·장소는 앱에서 등록 |

두 모드 공통: RFC 3161 타임스탬프(freetsa.org, 실패하면 sectigo)·검증 페이지·검증 파일·앱 꺼짐 거리 보정·메모 체인·화면 테마(설정).
코드에서는 `BuildConfig.DEMO` 로 나뉜다. 빌드는 처음 한 번 인터넷이 필요하다(BouncyCastle 내려받기, 그 뒤 `--offline` 가능).

```bash
export JAVA_HOME=~/opt/jdk-17.0.21+9
cd ~/Developments/chagok/app-studio
./gradlew assembleDemoDebug assembleRealDebug
cp app/build/outputs/apk/demo/debug/app-demo-debug.apk app/build/outputs/apk/real/debug/app-real-debug.apk /mnt/c/Android/dl/
$WA -s emulator-5570 install -r 'C:\Android\dl\app-demo-debug.apk'   # 또는 app-real-debug.apk. Windows adb 는 Windows 경로로 준다
# 모드를 바꿔 깔면 앞 모드의 기록이 남으므로 지운다
$WA -s emulator-5570 shell pm clear --user current ai.chagok.app
# 실제 동작 모드는 차량 권한 창이 뜬다. 촬영·시험에서는 미리 승인해 둔다
for p in FUSED_LOCATION; do   # 나머지 차량 권한은 설치할 때 자동으로 부여된다
  $WA -s emulator-5570 shell pm grant --user current ai.chagok.app pleos.car.permission.$p; done
```

에뮬레이터에서 실제 동작 모드를 켜면: 배터리 SoC(100%)·차량 정보는 SDK 에서 읽힌다. Virtual Console 이 값 쓰기를 막아(`emulator_inactive`)
계기판·기어 값이 바뀌지 않으므로 운행·주유 기록은 생기지 않는다. 그 흐름은 데모 모드로 보여 준다.

## 음성·Gleo·내비 (정비 비용 입력과 앱이 먼저 묻는 대화)

실제 동작 모드가 쓰는 Pleos SDK: `SpeechToText 3.3.1`, `TextToSpeech 3.3.1`, `NaviHelper 3.0.5` (Pleos Playground Maven, 설정 파일에 이미 있음).
에뮬레이터(Pleos Connect v2.1.0)에 설치된 서비스: 음성 `ai.pleos.playground.caas 4.2.10`, 내비 `ai.pleos.playground.service.navi 3.0.4`, Gleo `ai.umos.ambientai`. SDK 가 요구하는 최소 버전 이상이다.

- **음성(STT·TTS) 인증 정보:** 에뮬레이터에서 TTS·STT 를 요청하면 `Client credentials missing for ai.chagok.app` 로 서버가 거절하고, 기기 내 엔진은 에뮬레이터에 없어 `ON_DEVICE_ENGINE_ERROR` 가 난다. 그래서 인증 정보가 없으면 앱이 안드로이드 TTS 와 화면 입력으로 대신한다(설정 → 음성 점검).
  Pleos Playground 프로젝트의 client ID·secret 이 있으면 `~/.gradle/gradle.properties` 에 `pleos.clientId=…`, `pleos.clientSecret=…` 를 적고 실제 모드를 다시 빌드한다 (저장소에는 넣지 않는다). **이 경로는 인증 정보가 없어 확인하지 못했다.**
- **Gleo:** 앱 함수는 `app/src/main/assets/schema.json` 의 `ADD_EXPENSE`(종류·금액·곳), `QUERY_REPORT`(항목·기간·동작) 두 개이고 세 번째 칸은 비워 뒀다. Pleos 앱 마켓에 앱을 등록할 때 이 파일을 낸다.
  앱은 인텐트 액션 `ADD_EXPENSE`/`QUERY_REPORT` 와 JSON extra `ACTION_PARAM` 으로 받는다고 가정했다(Gleo 가 실제로 보내는 모양은 확인하지 못했다). adb 로 시험:
  ```bash
  $WA -s emulator-5570 shell "am start -a ADD_EXPENSE -p ai.chagok.app -c android.intent.category.DEFAULT --es ACTION_PARAM '{\"category\":\"toll\",\"amount_won\":4800,\"place\":\"하이패스\"}'"
  $WA -s emulator-5570 shell "am start -a QUERY_REPORT -p ai.chagok.app -c android.intent.category.DEFAULT --es ACTION_PARAM '{\"category\":\"toll\",\"period\":\"this_month\",\"action\":\"show\"}'"
  ```
- **내비 도착:** 실제 모드는 `NaviHelper` 의 `onDestinationArrived`/`onWayPointArrived` 로 도착을 받고, 도착 이벤트에 좌표가 없어 앱이 아는 현재 위치를 쓴다. 내비가 안 붙으면 운행이 끝난 위치로 대신 판정한다.
- 시연 모드의 시연 컨트롤은 네 줄(줄마다 버튼 4개 이하)이다. 아래 줄: Gleo 음성 명령, 비용 입력 열기, 경유지 도착, 정비 시기 임박, 연동 내역 가져오기.

## 시연 자동 촬영 (GPU 창 모드, 주간 약 2분 30초 + 야간 약 1분)

```bash
cd ~/Developments/chagok/app-studio/tools   # osm-tiles 는 ~/Developments/chagok/tools/osm-tiles 를 링크
D=~/Developments/chagok/screenshots-$(date +%Y%m%d-%H%M%S)
./emu-restart.sh gpu && ./incar-capture-fast.sh emulator-5570 $D
./emu-restart.sh gpu && ./incar-capture-night.sh emulator-5570 $D-night $D   # 켠 직후 야간으로 바꾸고 앱을 띄운다
# 미니PC 화면을 쓸 수 없을 때(창을 못 띄울 때)는 gpu 대신 cpu 로 켜고 SIM_SPEED=1 DOUBLE=1 ./incar-capture-fast.sh …
```

- 데모 APK 를 깔고 찍는다. 주간 35장(1칸 17 · 2칸 18: 09a~d 위변조 검증·검증 페이지, 10 앱 꺼짐 보정, 11 화면 테마 포함, 인터넷 필요) + 1칸 지도 합성본, 야간 홈 2장(+1칸 합성본). Z: 에는 1칸은 합성본만 넣는다
- 찍기 전에 dumpsys 로 앱 창의 실제 bounds 를 읽어 파일 이름의 `_1col`/`_2col` 과 맞을 때만 찍는다
- `DOUBLE=1` 이면 0.7초 간격으로 두 번 찍어 같을 때만 저장한다(CPU 모드용). GPU 창 모드는 지난 화면이 찍히지 않아 기본은 끔
- 운행 장면은 앱이 logcat 에 남기는 `trip started`/`trip ended` 로 확인한다. 시작을 본 뒤 2칸·1칸을 찍고, 그때까지 운행이 끝나지 않았는지 본다.
  운행 중에는 화면이 계속 바뀌어 화면 읽기(uiautomator)가 10초쯤 멈춰 기다리므로 쓰지 않는다
- 빨리 감기는 기본 2배 (`SIM_SPEED`). 기록 결과는 실제 시연과 같다
- 찍은 뒤에는 한 장씩 열어 본다
- 기본 adb 는 Windows `adb.exe`. 다른 adb 를 쓰려면 `ADB_BIN=… ./incar-capture-fast.sh …`
- 옆 칸 내비가 검게 남은 장면은 같은 촬영의 기준 장면 내비 칸으로 채운다 (`NavPanePaste.java`)

### GPU 모드: 창을 띄우면 된다 (2026-10-28 시험)

GPU(`-gpu host`) 에뮬레이터를 **`-no-window` 로 창 없이** 돌리면, 창이 2칸으로 바뀐 뒤나 야간으로 바꾼 뒤 화면을 다시 그리지 않는다.
그래서 `screencap` 이 지난 1칸 화면이나 어긋난(줄이 밀린) 화면을 돌려준다. 창 관리자 기준으로는 앱이 정상 2칸이다.
같은 날 GPU 창 없음 촬영에서 2칸 11장이 모두 이렇게 틀렸다.

Windows 화면에 창을 띄우면(`./emu-restart.sh gpu`) 2칸 11장, 2칸↔1칸 전환, 앱이 떠 있는 상태의 야간 전환이 모두 정상으로 찍혔다.
주간 21장 촬영은 3분 7초 걸렸다(CPU 모드 3분 50초). 에뮬레이터 자체 캡처(`emu screenrecord screenshot`)는 대화상자의 어두운 막이 빠지므로 쓰지 않는다.
2026-10-28 저녁 GPU 창 모드에서 2칸 장면이 지난 화면 조각으로 깨져(창이 가려졌을 때로 보임) CPU 모드(`SIM_SPEED=1 DOUBLE=1`)로 다시 찍어 Z: 에 넣었다 (주간 33장 + 기존 야간 2장). 2026-10-28 Z: 에 넣은 23장은 CPU 모드 촬영본이다. 그 뒤 촬영 기본값을 GPU 창 모드로 바꿨다. 어느 모드든 찍은 뒤 한 장씩 열어 본다.

| 방식 | 주간 21장 |
|---|---|
| Windows 에뮬레이터, CPU 렌더링(SwiftShader), 이중 캡처, 1배 | 약 4분 |
| **Windows 에뮬레이터, Intel Arc GPU, 창 띄움, 창 판정만, 2배 (기본)** | **2분 30초** |
| Windows 에뮬레이터, Intel Arc GPU, 창 띄움, 이중 캡처, 1배 | 3분 7초 |
| Windows 에뮬레이터, Intel Arc GPU, 창 없음 | 2분 16초 (2칸 장면이 틀림) |

## 다시 설치할 때

1. `emulator-windows_x64-16428233.zip`, `platform-tools_r37.0.1-win.zip` (dl.google.com/android/repository) 을 `C:\Android\Sdk` 에 푼다
2. `crp_car_emulator.zip` 을 `C:\Android\Sdk\system-images\android-34\pleos_car_emulator` 에 풀고, 생긴 `crp_avd_out_x86_64` 를 `x86_64` 로 이름 바꾼 뒤 `package.xml` 을 넣는다
3. `C:\Android\avd\pleos17.ini`, `pleos17.avd\config.ini` 를 만든다 (`image.sysdir.1=system-images\android-34\pleos_car_emulator\x86_64\`, `hw.lcd.width=2560`, `hw.lcd.height=1440`, `hw.lcd.density=160`, `hw.gpu.mode=host`)
4. WSL `~/.android/adbkey*` 를 `C:\Android\home` 에 복사한다 (없으면 adb 가 `unauthorized`)

## 참고: WSL 안 에뮬레이터로 GPU 를 쓰지 못한 이유

WSL 의 Mesa d3d12 경로로 Intel Arc 를 잡을 수는 있었지만, OpenGL ES 3.1 기능을 켜면 큰 GPU 메모리 할당을
WSL 의 GPU 통로(dxg)가 `dxgkio_create_allocation: Ioctl failed: -75 (EOVERFLOW)` 로 거부하고 장치가 끊겼다.
3.1 을 끄면 부팅은 되지만 왼쪽 차량 그래픽이 'OpenGL ES 3.1 이상 필요' 로 그려지지 않았다.
에뮬레이터 37.3.5, 화면 크기 축소, 기능·확장 끄기로도 같았다. Windows 에뮬레이터는 이 통로를 거치지 않는다.

## Virtual Console (차량 값 조작)

기기 8088 포트에 Virtual Console 웹서버가 있다. 읽기(`/vehicle/state`)는 되지만 쓰기는 `emulator_inactive` 로 거부된다.

## 실사용 스크린샷 촬영 규칙

- 화면에는 Popolog를 하나만 표시한다. 개발용 별도 패키지로 데이터를 격리하더라도 다른 Popolog 시연 앱과 나란히 띄워 촬영하지 않는다.
- 2col은 Popolog 하나가 앱 영역 두 칸을 사용하는 배치다. 1col은 Popolog 옆에 실제 내비게이션 등 다른 용도의 앱이 있는 배치로 촬영한다.
- 파일명의 1col/2col은 전체 디스플레이가 아니라 Popolog Activity의 실제 창 크기로 판단한다. 시스템 화면과 앱을 합성하거나 사용 불가능한 구성을 제출용 이미지로 남기지 않는다.
