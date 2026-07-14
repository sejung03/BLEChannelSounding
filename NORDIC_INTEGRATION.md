# Nordic Initiator Integration

## GATT contract

| Item | UUID | Properties | Security |
|---|---|---|---|
| Service | `8e400001-f315-4f60-9fb8-838830daea50` | Primary service | - |
| RX | `8e400002-f315-4f60-9fb8-838830daea50` | Write, Write Without Response | Encrypted write |
| TX | `8e400003-f315-4f60-9fb8-838830daea50` | Indicate | Encrypted read/CCCD |
| Status | `8e400004-f315-4f60-9fb8-838830daea50` | Read | Encrypted read |

`ReflectorGattContract.kt`가 UUID의 단일 소스입니다. Nordic firmware에서 UUID를 변경해야 하면 Android도 이 파일만 변경하면 됩니다.

Status characteristic은 ASCII를 반환합니다.

```text
CS-RSP;bond=1;oob=1
```

## Connection sequence

1. Service UUID로 Pixel advertisement를 scan합니다.
2. Connectable advertisement에 연결합니다.
3. GATT service를 discover합니다.
4. Pixel과 BLE bond를 완료합니다.
5. TX CCCD에 `0x0002`를 써서 indications를 활성화합니다.
6. Pixel 화면의 Ranging session이 `Ready`인지 확인합니다.
7. RX characteristic에 완전한 OOB `Ranging Capability Request` 한 개를 씁니다.
8. TX indication으로 `Ranging Capability Response`를 받고 indication을 confirm합니다.
9. RX로 `Ranging Configuration`을 보냅니다.
10. Nordic Bluetooth stack에서 연결된 Pixel을 상대로 CS procedure를 시작합니다.
11. Nordic에서 CS 결과를 계산하고 보고합니다.
12. Nordic에서 CS를 중지합니다. 필요하면 OOB `Stop Ranging`도 전송합니다.

`Ranging Configuration Response`와 `Stop Ranging Response`는 OOB 규격상 optional입니다. Nordic state machine은 해당 응답이 없어도 진행할 수 있어야 합니다.

## OOB messages

Android 16의 기본 호환 기준은 OOB version 1입니다. Header는 2 bytes입니다.

| Offset | Field |
|---|---|
| 0 | Version (`0x01`) |
| 1 | Message ID |

| Message | ID |
|---|---|
| Ranging Capability Request | `0x00` |
| Ranging Capability Response | `0x01` |
| Ranging Configuration | `0x02` |
| Ranging Configuration Response | `0x03` |
| Stop Ranging | `0x06` |
| Stop Ranging Response | `0x07` |

BLE CS technology bit은 `0x02`이고 technology ID는 `0x01`입니다. 별도 표기가 없는 multi-byte numeric field는 little-endian입니다. BLE address field만 규격에 따라 big-endian입니다.

Android 앱은 OOB payload를 직접 생성하거나 해석하지 않습니다. RX write 전체를 `TransportHandle.onReceiveData()`에 전달하고 Android Ranging module이 생성한 response 전체를 TX indication으로 전송합니다. 따라서 Nordic도 AOSP OOB 규격의 완전한 message를 사용해야 합니다.

## ATT transport rules

- RX write 하나는 OOB message 하나와 정확히 대응합니다.
- TX indication 하나는 OOB message 하나와 정확히 대응합니다.
- Android Ranging module은 fragment가 아닌 완전한 message를 요구합니다.
- CS-only OOB v1 message는 기본 ATT MTU 23 안에 들어가지만 MTU 247 이상을 권장합니다.
- TX는 notification이 아니라 indication입니다. Nordic은 매 frame을 confirm해야 합니다.
- 앱은 동시에 한 Nordic peer만 허용합니다.

## Bond and CS control

BLE CS는 두 장치 사이의 기존 bond를 요구합니다. RX와 TX CCCD가 encrypted permission으로 구성되어 있어 bond 이전 접근은 Android Bluetooth stack에서 거부하거나 pairing을 유도합니다.

Reflector는 CS start/stop HCI procedure를 호출하지 않습니다. Nordic Initiator가 CS를 시작하면 Pixel controller가 in-band 절차를 통해 Reflector로 동작합니다.

## Expected Android log sequence

```text
Reflector service started
BLE CS capability: AVAILABLE
GATT service ready
Advertising started
GATT connected
Bond state: bonded
OOB indications enabled
Opening Android Ranging responder session
Ranging responder session open
RX OOB v1 Capability request
TX OOB v1 Capability response
RX OOB v1 Ranging configuration
Ranging started: BLE CS
```

## Firmware checklist

- LE Extended Feature에서 Channel Sounding Initiator 지원 확인
- Android peer와 secure bond 유지
- TX indications 활성화 후 OOB 시작
- capability response의 CS security level과 peer address 사용
- Pixel이 지원하는 security level 중 하나 선택
- OOB와 CS controller event를 별도 timeout으로 관리
- disconnect 시 CS 및 OOB state를 모두 초기화

## References

- [AOSP OOB v1 specification](https://source.android.com/docs/core/connect/ranging-oob-spec)
- [AOSP OOB v3 specification](https://source.android.com/docs/core/connect/ranging-oob-spec-v3)
- [Android Ranging API](https://developer.android.com/develop/connectivity/ranging)
