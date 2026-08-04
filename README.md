# Phone-to-Phone Bluetooth Channel Sounding

Android 16(API 36) 이상에서 두 대의 휴대폰을 Initiator / Reflector(Responder)로 선택하여
Bluetooth Channel Sounding 거리를 측정하는 예제입니다.

## 동작 순서

1. Reflector 휴대폰: 역할을 Reflector로 선택하고 `Reflector 광고 시작`.
2. Initiator 휴대폰: 역할을 Initiator로 선택하고 `Reflector 검색`.
3. 검색된 장치를 선택하고 `연결 및 페어링`.
4. Bond와 GATT notification 설정이 완료되면 `CS 시작` 버튼이 활성화됨.
5. Initiator가 GATT `START`를 전송.
6. Reflector가 Raw Responder RangingSession을 열고 `READY`를 notify.
7. Initiator가 Raw Initiator RangingSession을 시작.
8. Initiator가 측정한 거리 결과를 GATT로 Reflector에 전달하여 양쪽 화면에 표시.
9. CS 세션의 BLE/CS 절차 로그와 거리 결과를 양쪽 휴대폰에서 CSV로 저장.

## CSV 로그 저장

CS 세션을 시작하면 각 휴대폰의 공용 `다운로드/PhoneCS` 폴더에 역할별 CSV 파일을 자동으로 생성합니다.
Android MediaStore를 사용하므로 별도의 파일 저장 권한은 필요하지 않습니다.

- Initiator: `CS 시작` 버튼을 누를 때 파일 생성
- Reflector: Initiator의 GATT `START` 요청을 받을 때 파일 생성
- 파일명: `yyyyMMdd_HHmmss_SSS_CS_INITIATOR.csv` 또는 `yyyyMMdd_HHmmss_SSS_CS_REFLECTOR.csv`
- 세션 종료, 오류 또는 연결 해제 시 파일 저장 완료
- 한글 로그의 Excel 호환을 위해 UTF-8 BOM 사용

BLE/CS 이벤트와 거리 데이터는 하나의 CSV에 행 단위로 기록하며 다음 열로 구분합니다.

| 열 | 설명 |
| --- | --- |
| `timestamp` | ISO 8601 형식 기록 시각 |
| `elapsed_ms` | CSV 세션 시작 후 경과 시간(ms) |
| `role` | `INITIATOR` 또는 `REFLECTOR` |
| `record_type` | `EVENT` 또는 `DISTANCE` |
| `source` | 로그 출처(`BLE`, `CS`, `APP`, `STORAGE`, `LOCAL`, `REMOTE`) |
| `event` | BLE/CS 절차 및 상태 메시지 |
| `raw_distance_m` | Android Ranging API가 전달한 원본 거리(m) |
| `smoothed_distance_m` | 최근 최대 5개 원본 거리의 단순 이동평균(m) |
| `sample_count` | 해당 세션에서 수신한 누적 거리 샘플 수 |
| `peer_address` | 상대 휴대폰의 Bluetooth 주소 |

Initiator CSV의 거리 출처는 `LOCAL`, Reflector CSV의 GATT 전달 거리 출처는 `REMOTE`로 기록됩니다.

## 요구사항

- Android 16 / API 36 이상
- 두 휴대폰 모두 `RangingManager`의 `csCapabilities` 지원
- foreground 실행
- Bluetooth, Nearby Devices, Ranging 권한 허용

## 빌드

- Android Studio에서 프로젝트를 열어 Sync 후 실행
- compileSdk 36
- JDK 17
- AGP 9.2.1 / Gradle 9.4.1

Gradle wrapper JAR/스크립트는 저장소에 포함하지 않았습니다. Android Studio에서 Sync하거나 로컬 Gradle로
`gradle wrapper --gradle-version 9.4.1`을 실행하면 됩니다.

## 주의

- Android Ranging API는 BLE CS 거리 결과를 Initiator에만 전달하며, 이 앱은 해당 결과를 GATT로 Reflector에 중계합니다.
- 공개 Android API에서는 BLE CS 내부 거리 계산이 PBR, RTT 또는 두 방식의 조합인지 확인할 수 없습니다.
- `smoothed_distance_m`은 Android 제공값이 아니라 앱에서 계산하는 최근 최대 5개 거리의 단순 이동평균입니다.
- 제조사 Bluetooth firmware 구현에 따라 phone-to-phone Reflector 역할이 지원되지 않을 수 있습니다.
- `0x2A` 충돌을 줄이기 위해 Pairing과 GATT 준비가 끝난 뒤 Reflector session을 먼저 열고, READY 이후 Initiator session을 시작합니다.
