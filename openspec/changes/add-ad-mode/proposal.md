# Proposal

## Why

Popolog는 주행 중 앱 기능을 쓸 수 없으므로 주행 중 앱 화면은 비어 있다. 이 화면을 옵트인 광고 노출 지면으로 사용해 앱 운영 수익 구조를 검증한다. 세무·운행기록 앱의 신뢰를 지키기 위해 광고는 기록 데이터와 완전히 분리한다. 기준 문서: 프로젝트 폴더 `ad-platform/popolog-ad-mode-scenario.md`, `ad-platform-idea.md`.

## What Changes

- 설정에 "광고 모드"를 추가한다. 동의를 먼저 받고(처음 열 때), 동의하면 광고 모드와 소리 광고가 기본 켜짐으로 시작하며 설정에서 언제든 끌 수 있다.
- 켜져 있고 차가 출발하면 앱 전체 화면을 정지 이미지 광고 1장으로 바꾸고, 주행 중에는 변경하지 않는다. 우상단에 "광고" 라벨을 상시 표시한다.
- 광고 화면에는 "앱 화면으로" 버튼이 항상 있어 사용자가 원하면 정차 여부와 상관없이 눌러서 앱 화면으로 갈 수 있다. 자동 복귀는 없고, 복귀하면 다음 광고 이미지로 교체하며 같은 주행 중에는 광고를 다시 띄우지 않는다.
- 소리 광고는 기본 켜짐이고 횟수 제한이 없으며(사용자가 끌 수 있음) 정차 시점(도착 전·주유/충전 직후)에만 제안한다. 음성 "광고 그만" 또는 설정 OFF로 해제한다.
- 차속은 추상화 계층(`SpeedSource`)으로 받는다. 실제 구현은 CarPropertyManager `PERF_VEHICLE_SPEED`이며 권한·3rd-party 허용이 미확인이므로 사용 불가 시 광고 모드는 동작하지 않는다(안전 측).
- 광고는 운행 기록·해시체인·세무 PDF와 분리한다.

## Capabilities

### New Capabilities

- `ad-mode`: 옵트인, 차속 기반 전체 화면 광고 전환, 소리 광고 빈도 제한, 해제, 기록 분리.

### Modified Capabilities

- `vehicle-platform-integration`: 차속 신호 추상화와 사용 불가 처리.
- `application-shell`: 광고 모드 전체 화면과 라벨, 데모 시연 조작.

## Impact

- 대상은 `popolog-pleos`의 `vehicle/VehicleGateway.kt`, `SimulatedVehicle.kt`, `PleosVehicleGateway.kt`(src/real), 신규 `core/AdModePolicy.kt`, `ui/AdScreen.kt`, `AppController.kt`, `MainActivity.kt`, `ui/SettingsScreen.kt`다.
- 범위 밖: 광고 서버·과금·실제 광고주 연동·영상 광고, 위치 기반 광고 선정(데모는 고정 샘플 이미지), 법적 판단 확정. 도로교통법 해석과 Pleos Driving Restriction Policy는 미확인이며 이 변경에서 확정하지 않는다.
- 광고 노출·소리 횟수는 세무·운행 데이터에 저장하거나 해시체인에 넣지 않는다.
