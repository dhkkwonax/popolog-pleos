# Popolog: 차가 스스로 쓰는 업무용 차량 장부

현대자동차그룹 Pleos 앱 공모전 출품작입니다. 차량 계기판 누적거리와 위치로 운행을 자동으로 기록하고 분류합니다.
주유·충전은 감지한 뒤 금액만 음성으로 받습니다. 운행기록은 국세청 고시의 항목과 격자형 배치에 맞춘 PDF·CSV로 내보냅니다. 설정에서 사업자 유형을 고르며(기본: 개인사업자, 소득세법 고시 제2025-5호 / 법인: 법인세법 고시 제2025-8호) 두 서식은 머리글·합계 용어만 다릅니다. 운전자 추정과 세무 서식의 차량 이용자 기재는 분리되어 있으며, 운행별 이용자를 확인한 뒤 이름·부서를 출력합니다.
기록마다 해시체인을 쌓고, 하루가 끝나면 외부 기관의 RFC 3161 타임스탬프를 받아 위변조를 검증합니다.

## 설치 (APK)

| 파일 | 모드 | 쓰임 |
|---|---|---|
| [`apk/Popolog-demo.apk`](apk/Popolog-demo.apk) | 데모 모드 | 심사·시연용. 가상 차량, 가상 시계, 시연 컨트롤, 6월 이후 예시 데이터 |
| [`apk/Popolog-real.apk`](apk/Popolog-real.apk) | 실제 동작 모드 | Pleos 에뮬레이터·실차용. Pleos SDK 값만 쓰고, 예시 데이터와 시연 컨트롤은 없음 |

```bash
adb install -r apk/Popolog-demo.apk
# 실제 동작 모드는 차량 권한 창이 뜹니다 (위치·주행거리·에너지·차량 정보)
```

두 APK는 패키지 이름(`ai.chagok.app`)이 같아 한 번에 하나만 설치됩니다. 모드를 바꿔 설치할 때는 `adb shell pm clear ai.chagok.app` 로 앞 모드의 기록을 지우세요.
가로 화면(차량 디스플레이) 기준으로 만들었고, Pleos Connect 17″ 에뮬레이터(2560×1440)에서 확인했습니다. minSdk 29, targetSdk 34.

## 데모 모드에서 해 볼 것

화면 오른쪽 위의 **시연** 버튼으로 시연 컨트롤을 엽니다.

| 조작 | 앱이 보여 주는 것 |
|---|---|
| 도착지 고르기 (회사·거래처·집·미등록 장소) | 운행 자동 기록과 목적 자동 분류. 미등록 장소에 도착하면 "업무였나요?"를 물음 |
| OOO 탑승 | 운전석 시트 위치로 운전자 판별 |
| 주유하기 / 충전하기 | 연료·배터리 상승을 감지하고 결제 금액을 물음 |
| 도중 정차 (6분, 주행 중) | 정차가 5분을 넘으면 운행을 나눔 |
| 시동 끄고 다시 켜기 | 주행 중이면 그 자리에서 운행 종료, 다시 켜면 운전자 재판별 |
| 앱 끈 채 운행 (12km) | 앱이 꺼진 동안 늘어난 계기판 거리를 보정 운행으로 기록 |
| 가솔린 ↔ 전기차 전환 | 주유 감지와 충전 감지 |
| 기록 위조해 보기 / 되돌리기 | 세무 리포트의 '기록 무결성' 카드가 몇 번째 기록부터 어긋났는지 보여 줌 |
| 하루 마감 | 오늘 기록을 앵커로 묶고 RFC 3161 타임스탬프를 요청 |

세무 리포트의 **운행기록부 PDF**에서 미리보기, 검증 페이지(체인 재계산·QR·타임스탬프 대조), 검증 파일(JSON) 저장을 할 수 있습니다.
설정에서는 화면 테마(자동·주간·야간)와 운전자를 바꿀 수 있습니다.

## 빌드

빌드 옵션(product flavor)이 곧 모드입니다. 코드에서는 `BuildConfig.DEMO` 로 나뉩니다.

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDemoDebug    # 데모 모드   → app/build/outputs/apk/demo/debug/app-demo-debug.apk
./gradlew assembleRealDebug    # 실제 동작 모드 → app/build/outputs/apk/real/debug/app-real-debug.apk
./gradlew testDemoDebugUnitTest
```

실제 동작 모드는 Pleos Playground 저장소의 `ai.pleos.playground:Vehicle:2.3.2`, `ai.pleos.playground:FusedLocation:1.1.3` 를 씁니다.
이 라이브러리는 `settings.gradle.kts` 에 적힌 Pleos Playground 저장소(nexus-playground.pleos.ai)에서 받습니다. 데모 모드는 Pleos 라이브러리 없이 빌드됩니다.

## 구조

| 경로 | 내용 |
|---|---|
| `app/src/main/kotlin/ai/chagok/app/core` | 운행 구간 감지, 주유·충전 감지, 장소 기반 분류, 운전자 판별, 한국어 금액 파서, 세무 집계·CSV, 해시체인(`Integrity`), RFC 3161(`Rfc3161`) |
| `app/src/main/kotlin/ai/chagok/app/ui` | 홈·장부·운행일지·세무 리포트·장소·설정 화면, 운행기록부 PDF, 시연 컨트롤 |
| `app/src/main/assets/verify.html` | QR이 여는 검증 페이지 (체인 재계산, 타임스탬프 지문 대조) |
| `app/src/demo` | 데모 모드의 가상 차량 연결 |
| `app/src/real` | 실제 동작 모드의 Pleos Vehicle·FusedLocation SDK 어댑터 |
| `tools/` | 에뮬레이터 자동 촬영 스크립트 |
| `HOWTO-pleos.md` | Pleos Connect 에뮬레이터 실행·촬영 방법 |

## 알아 둘 점

- 개인정보: 기록은 이 기기에만 저장합니다. 앱 안 "개인정보 처리 안내"(설정, 실제 모드는 첫 실행 때 표시)는 초안이며, 운영 주체·연락처·보유 기간은 배포 전에 채워야 합니다. 설정에서 외부 타임스탬프 전송을 끄거나 모든 기록을 삭제할 수 있습니다(실제 모드).
- 세무 계산은 법인세법 시행령 제50조의2를 단순화한 참고용 추정치입니다.
- 타임스탬프 기관(freetsa.org, 실패하면 sectigo)에는 날짜와 해시의 SHA-256 지문만 보냅니다. freetsa.org는 무료 공개 기관이라 공인 전자문서 기관은 아닙니다.
- Pleos 에뮬레이터는 Virtual Console의 값 쓰기를 막아 두어서 계기판·기어 값이 바뀌지 않습니다. 실제 동작 모드에서 운행·주유 흐름은 실차에서 동작하고, 에뮬레이터에서는 데모 모드로 보여 줍니다.
