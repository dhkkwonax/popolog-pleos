# Tasks

## 1. 차속 입력

- [x] 1.1 `VehicleGateway.Listener.onSpeed(Double?)`를 추가하고 `SimulatedVehicle`이 주행 60·정차 0을 보내도록 한다. 완료 확인: 데모 가상 주행에서 차속 변화가 컨트롤러에 전달된다.
- [ ] 1.2 (코드 작성 완료, 기기 확인 대기) `PleosVehicleGateway`에 `PERF_VEHICLE_SPEED` 읽기(`CarPropertySpeedSource`, 리플렉션)를 연결한다. 완료 확인: 불가 시 `null`을 보내며 기존 기능에 영향이 없다. 권한 값·3rd-party 허용은 에뮬레이터·기기에서 확인 전까지 미완료 표시를 유지한다.

## 2. 정책과 상태

- [x] 2.1 `core/AdModePolicy.kt`: NORMAL/AD 상태 기계, 정차 4초 복귀, 이미지 교체 시점, 오래된 속도 처리.
- [x] 2.2 소리 광고 하루 2회 제한과 정차 시점 제한. 날짜 변경 시 초기화.
- [x] 2.3 `AppController`에 옵트인 동의·토글·"광고 그만" 처리·정책 연결을 추가한다. 광고 상태를 기록·체인·세무에 쓰지 않는다.

## 3. 화면

- [x] 3.1 `ui/AdScreen.kt`와 `MainActivity` 전체 화면 전환(라벨, 안내 문구, 이미지 고정).
- [x] 3.2 설정 화면 "광고 모드" 카드(동의 안내, 켜기/끄기, 정차 중에만 변경).
- [ ] 3.3 (미실행) 데모 시연 순서 확인: 설정 ON → 가상 출발 → 광고 고정 → 정차 → 복귀·교체 → 충전 감지 → 음성 광고 → 세무 리포트에 광고 없음.

## 4. 확인

- [ ] 4.1 (미실행: 이 작업 환경에 Android SDK가 없어 컴파일하지 못했다. 로컬에서 `./gradlew :app:compileDemoKotlin :app:compileRealKotlin` 필요) 데모·실제 모드 Kotlin 컴파일로 API 연결을 확인한다. 테스트 추가·실행은 사용자 요청 전까지 하지 않는다.
- [ ] 4.2 에뮬레이터에서 `PERF_VEHICLE_SPEED` 수신, 권한, 광고 화면 표시 확인. 법적·정책 확인(Driving Restriction Policy, 앱 마켓 광고 정책)은 별도 문의 후 기록한다.

## Notes

- 소리 광고의 "안내해줘"는 경유지 추가를 아직 연결하지 않았다(NaviHelper 경유지 API 연동은 별도 변경). 현재는 요청만 알림으로 남긴다.
- `android.car.permission.CAR_SPEED` 등 차속 권한은 매니페스트에 추가하지 않았다. 확인된 권한 값이 나오면 반영한다.
