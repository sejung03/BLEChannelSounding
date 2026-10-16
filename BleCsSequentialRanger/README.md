# BLE CS 4-Board Sequential Ranger

Android 스마트폰을 **BLE Channel Sounding initiator**, 네 개의 보드를 **reflector**로 사용해
`A 측정·CSV 저장 → B 측정·CSV 저장 → C 측정·CSV 저장 → D 측정·CSV 저장 → A ...` 순서로 계속 측정하는 Android Studio 프로젝트입니다.

## 구현된 기능

- 네 보드를 A→D 순서로 GATT 연결한 뒤 연결 상태를 유지
- GATT 147 발생 시 이전 객체를 폐기하고 2~5초 backoff로 새 연결 생성
- 검증된 단일 RP 세션을 A→B→C→D 순서로 실행
- 보드 간 세션 정리 guard time 25ms
- 빠른 BLE CS 갱신(`UPDATE_RATE_FREQUENT`, 약 100ms)과 Security Level 1
- 네 거리, 보드별 측정 소요 시간 로그, 전체 사이클 시간과 횟수 표시
- 100m 초과/음수/비유한 거리값을 결과에서 제외하여 `999m` 방지
- 개별 보드가 실패하면 100ms 후 1회 재시도하고, 시도당 1.2초 후 다음 보드로 넘어감
- 실제 하드웨어 없이 흐름을 확인하는 시뮬레이션 모드
- MAC 주소 자동 저장
- Static/Dynamic 실험 구분 선택 및 연구용 Cycle/Attempt CSV 분리 저장
- 가속도·자이로스코프·Game Rotation Vector·지자기를 20ms 간격(50Hz)으로 Cycle CSV에 기록
- 앱에서는 위치를 계산하지 않으며, 저장한 거리로 외부 분석 코드에서 위치 계산

## CSV 측정 로그

반복 측정을 시작할 때마다 `Download/BleCsSequentialRanger`에 다음 두 파일을 새로 만듭니다.

- `ble_cs_날짜_시간_cycle_summary.csv`: 보드별 BLE CS 측정 행과 50Hz IMU 행을 함께 저장
- `ble_cs_날짜_시간_attempt_log.csv`: 최초 시도, 원시 결과, 실패, 재시도 예약을 이벤트당 한 행

화면의 `Dynamic 실험` 스위치 상태는 두 파일의 `experiment_condition`에 `STATIC` 또는
`DYNAMIC`으로 기록됩니다.

Cycle Summary에는 절대 UTC 시각과 다음 데이터를 저장합니다.

- 세션 ID, 실험 조건, 소스 유형(`BLE_CS`/`SIMULATION` 또는 `IMU_50HZ`), 사이클 번호
- 사이클 번호; 개별 보드 행의 `cycle_elapsed_ms`, `cycle_valid`는 빈 값
- RP1~RP4의 MAC과 거리
- 3축 가속도(m/s²), 3축 자이로스코프(rad/s)
- Game Rotation Vector(w, x, y, z), 3축 지자기(µT)

IMU는 네 센서의 최신 값을 하나의 행으로 묶어 20ms 타임라인으로 기록합니다. IMU 행에서는 Cycle/RP
열이 비어 있고, BLE CS 보드 행에서는 IMU 열이 비어 있습니다.
각 보드의 유효한 거리 결과를 받으면 해당 RP의 MAC과 거리만 채워 즉시 저장하고 flush합니다.
다른 RP 열은 비우며, 3개 이상의 결과나 사이클 종료를 기다리지 않습니다.
절대 UTC 시각은 해당 보드 측정 시각이며, 측정 타임스탬프가 없으면 결과 수신 시각을 사용합니다.
사이클 종료 시에는 추가 거리 행을 저장하지 않습니다. 실패·타임아웃·비정상 거리는 Attempt 로그에 기록됩니다.

`cycle_representative_utc`, RP별 측정 UTC, RP별 상태, 추정 X/Y, 잔차 RMSE는 저장하지 않습니다. 앱 내부의 삼변측량도
제거했으므로 위치와 실제 위치 기준 오차는 외부 분석 코드에서 계산해야 합니다.

Attempt 로그는 19개 열을 저장하며 `ATTEMPT_STARTED`, `VALID_DISTANCE`, `INVALID_DISTANCE`,
`MISSING_DISTANCE`, `ERROR`, `TIMEOUT`, `RETRY_SCHEDULED` 상태를 구분합니다. 예를 들어 보드가
`999m`를 반환하면 `raw_distance_m`에는 999가 그대로 남고 `accepted_distance_m`은 비워집니다.
따라서 실패율과 재시도 효과는 전체 Attempt 로그로 계산하고, 거리·위치 분석에는
`accepted_distance_m` 또는 보드별 거리 행을 사용할 수 있습니다. 전체 Cycle 분석은 `session_id`와
`cycle`을 기준으로 보드별 행을 묶어 수행합니다.

## 필수 조건

1. Android 16(API 36) 이상 스마트폰
2. 스마트폰이 `android.hardware.bluetooth_le.channel_sounding` 기능을 지원할 것
3. 네 보드의 Bluetooth 컨트롤러와 펌웨어가 BLE Channel Sounding **reflector** 역할을 지원할 것
4. 보드가 입력한 주소로 GATT 연결 가능할 것

일반 BLE 보드는 앱 코드만으로 CS reflector가 되지 않습니다. 보드 제조사의 Bluetooth 6.0
Channel Sounding reflector 예제/SDK 설정이 별도로 필요합니다. 보드가 Resolvable Private Address를
계속 바꾸는 경우에는 고정 identity address, bonding, 또는 제품에 맞는 스캔/주소 해석 절차를 사용해야 합니다.

## 실행

1. Android Studio에서 이 폴더를 엽니다.
2. Gradle Sync를 실행합니다. 프로젝트는 AGP 9.2.1, Gradle 9.4.1, compile/target SDK 36을 사용합니다.
3. 우선 기본값인 **시뮬레이션 모드**에서 실행해 화면과 반복 루프를 확인합니다.
4. 실제 모드로 전환하고 A/B/C/D의 대문자 MAC 주소를 입력합니다.
5. 실험에 따라 `Dynamic 실험` 스위치를 설정합니다(끄면 Static).
6. `4개 모두 연결`을 눌러 네 상태가 모두 `연결됨`인지 확인합니다.
7. `반복 측정 시작`을 누릅니다.

실제 모드에서는 GATT 연결 후 Android 페어링 창을 승인해야 합니다. 화면 상태가 `연결됨·본딩됨`으로
바뀐 보드만 측정 준비가 완료된 것입니다. 펌웨어를 다시 올린 뒤에는 스마트폰의 기존 페어링 정보를
삭제하고 새로 페어링해야 이전 키와 보드의 새 키가 충돌하지 않습니다. 스마트폰에서만 페어링을
삭제했다면 보드에 예전 키가 남아 `PIN/패스키 오류`가 발생합니다. 본딩 복구 펌웨어에서는 스마트폰의
페어링을 삭제한 다음 보드의 Button 1을 눌러 보드 키도 삭제하고 다시 연결합니다.

명령줄 빌드:

```powershell
.\gradlew.bat assembleDebug
```

생성 APK: `app/build/outputs/apk/debug/app-debug.apk`

## 보드 전환 지연에 관하여

`CycleRangingController`는 A의 첫 유효 결과를 받으면 세션을 정리하고 25ms 후 B를 시작하며, 같은
방식으로 C와 D까지 진행합니다. 네 GATT 링크는 계속 유지합니다. 정상 경로에는 재시도가 추가되지 않고,
세션 오류나 타임아웃이 발생한 보드만 한 번 더 시도합니다.

다중 RP 지속 세션은 일부 스마트폰에서 A/B/C 결과 누락과 타임아웃을 일으키므로 사용하지 않습니다.
대신 단일 RP 세션 구조를 유지하면서 Android 공식 BLE CS `UPDATE_RATE_FREQUENT`(100ms)를 요청합니다.
따라서 이전 버전보다 빨라질 수 있지만, 공개 Android API만으로 1초 이내 Cycle을 보장할 수는 없습니다.
실제 Cycle 시간은 화면의 `최근 1사이클` 값으로 기록합니다.

단, 실제 무선 전환 시간의 하한은 Android Bluetooth 스택, 스마트폰 컨트롤러, 보드 펌웨어,
연결 interval, 동시 ranging-session 제한이 결정합니다. 앱이 0ms 지연을 요청해도 물리적인 0ms
전환을 보장할 수는 없습니다. 화면의 보드별 로그와 `최근 1사이클` 값은 `elapsedRealtimeNanos()`
기준으로 측정됩니다.

## 주요 코드

- `MainActivity.kt`: 권한, UI, 설정, 실제/시뮬레이션 모드
- `GattConnectionKeeper.kt`: 네 GATT 연결 유지와 자동 재연결
- `AndroidBleCsMeasurementSource.kt`: Android 16 단일 RP 호환 세션
- `CycleRangingController.kt`: A/B/C/D 순차 루프와 타임아웃, 사이클 시간

## 공식 API 자료

- https://developer.android.com/develop/connectivity/ranging
- https://developer.android.com/reference/android/ranging/RangingManager
- https://developer.android.com/reference/android/ranging/ble/cs/BleCsRangingParams
- https://developer.android.com/reference/android/ranging/raw/RawRangingDevice
