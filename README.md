# BLE CS Reflector

Pixel 10 Pro를 BLE Channel Sounding Reflector(안드로이드 명칭: Responder)로 사용하는 Android 16 앱입니다. Nordic 보드는 Initiator이며 거리 결과는 Nordic 쪽에서 처리합니다.

## 구현 상태

- Android 16 / API 36 `RangingManager` BLE CS capability 진단
- 암호화된 BLE GATT 서버와 connectable advertising
- 연결 후 자동 bond 요청
- GATT 기반 `TransportHandle` 구현
- Android OOB Responder session 구성
- OOB RX/TX frame 및 세션 이벤트 로그
- Foreground service 기반 장시간 실행
- 단위 테스트와 debug APK 빌드 완료

## 역할

```text
Nordic Initiator                         Pixel 10 Pro Reflector
----------------                         ----------------------
Scan/connect/bond  --------------------> BLE GATT server
OOB request        -- RX write --------> Android TransportHandle
OOB response       <- TX indication ---- Android Ranging module
Start/stop CS      =====================> Android Bluetooth stack
Distance result    <local on Nordic>
```

CS에서는 Initiator만 Bluetooth stack에 ranging 시작/중지를 요청합니다. Pixel의 저수준 Reflector 동작은 Android Bluetooth controller/stack이 처리합니다.

## 빌드

프로젝트는 AGP 8.10.1, Gradle 8.11.1, Kotlin 1.9.24를 사용합니다.

```powershell
$env:JAVA_HOME = "C:\Users\HALLYM\Documents\Codex\2026-07-10\g\work\jdk17-full\jdk-17.0.19+10"
./gradlew.bat testDebugUnitTest assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 실행

1. Pixel 10 Pro에서 개발자 옵션과 USB debugging을 켭니다.
2. 앱을 설치하고 `Start reflector`를 누릅니다.
3. Nearby devices, Ranging, Notification 권한을 허용합니다.
4. Nordic에서 서비스 UUID를 검색해 연결합니다.
5. Nordic에서 bond를 완료하고 TX indications를 활성화합니다.
6. Nordic에서 OOB Capability Request를 전송합니다.

Nordic 구현에 필요한 UUID와 순서는 [NORDIC_INTEGRATION.md](NORDIC_INTEGRATION.md)에 정리되어 있습니다.

## 주요 파일

- `ReflectorService.kt`: BLE 광고/GATT 서버, bond, Ranging Responder lifecycle
- `GattOobTransport.kt`: Android Ranging module과 GATT 사이 OOB transport
- `CsCapabilityMonitor.kt`: Pixel BLE CS capability 및 security level 확인
- `ReflectorGattContract.kt`: Nordic와 공유할 UUID
- `OobFrameInspector.kt`: OOB 이벤트 로그용 frame header 검사

## 제약

- Android 16(API 36) 이상 전용입니다.
- Pixel이 runtime capability에서 BLE CS를 `Available`로 보고해야 합니다.
- CS 전에 Pixel과 Nordic 사이 bond가 반드시 있어야 합니다.
- 앱은 responder 측 거리값을 표시하지 않습니다. Android는 BLE CS 결과를 Initiator에만 전달합니다.
- 현재 GATT transport는 한 번의 RX write 또는 TX indication에 완전한 OOB message 하나를 전달합니다.

## 참고

- [Android Ranging guide](https://developer.android.com/develop/connectivity/ranging)
- [Android Ranging OOB v1](https://source.android.com/docs/core/connect/ranging-oob-spec)
- [Android Ranging OOB v3](https://source.android.com/docs/core/connect/ranging-oob-spec-v3)
