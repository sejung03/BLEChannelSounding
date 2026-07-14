# v11 Pixel Reflector 호환 시험본

이 폴더는 원본 `C:\ncs\v3.3.1\ras_initiator`를 직접 수정하지 않고 만든
`nrf54l15dk/nrf54l15/cpuapp`용 시험 펌웨어와 전체 소스입니다.

## 보드에 올릴 파일

`firmware/ras_initiator_android_reflector_v11_pixel_compat_nrf54l15dk.hex`

## v10 대비 변경값

- BLE 연결 간격: 20 ms (`0x10`)에서 30 ms (`0x18`)로 변경
- CS Procedure interval: `7`로 고정
- 최대 CS Procedure 길이: `240` (`150 ms`)으로 고정
- CS Subevent 길이: `9820 us`로 고정
- Preferred peer antenna: 안테나 3 선택
- CS Channel Map: Pixel 호환 Medium 프리셋(37채널) 적용
- `0x1E` 발생 후 interval 10으로 변경하던 보조 재시도 제거
- 다른 LL procedure와 충돌하는 `0x2A`에 대한 지연/재시도는 유지

Medium Channel Map 바이트 배열:

```c
{ 0x54, 0x55, 0x55, 0x54, 0x55, 0x55, 0x55, 0x55, 0x55, 0x15 }
```

ATT MTU는 기존 498을 유지합니다. `0x1E`는 MTU/OOB 교환 이후 CS Procedure
Enable에서 발생했으므로, 이번 시험에서는 Procedure 협상에 직접 관여하는 값만
변경했습니다.

## 정상 적용 확인용 UART 로그

다음 줄이 보여야 v11이 올라간 것입니다.

```text
CS procedure parameters: max_len=240, interval=7, count=0, subevent=9820 us, peer_antenna=3, PHY=2M
```

`count`와 `PHY`는 상대 capability에 따라 달라질 수 있지만 `max_len=240`,
`interval=7`, `subevent=9820 us`, `peer_antenna=3`은 동일해야 합니다.

CS config 로그의 Channel Map도 기존 전체 72채널 값이 아니라 다음 값이어야 합니다.

```text
channel_map: 0x15555555555554555554
```

## 빌드 확인

- NCS: v3.3.1
- Board: `nrf54l15dk/nrf54l15/cpuapp`
- 빌드 결과: 성공
- FLASH: 329816 bytes / 1428 KB
- RAM: 68988 bytes / 188 KB

## SHA-256

```text
27F80BFB115E0E8F5AB895C5F787D1095CD26CEB53DFF5917EC9343058C72721  ras_initiator_android_reflector_v11_pixel_compat_nrf54l15dk.hex
69C0622C51CFA053A81AF8A7059567B95B951DE011395EED063FBED760E1FC96  ras_initiator_android_reflector_v11_pixel_compat_nrf54l15dk.elf
```

