# BLE Channel Sounding PhoneCS Stop-and-Save

이 버전은 PC 수집기에서 `Enter`를 누르면 Initiator 보드 측정을 중지하고 CSV를
정상적으로 닫습니다. 기존 PhoneCS HEX와 별도로 만든 버전입니다.

## 펌웨어

- 파일: `ras_initiator_phonecs_stop_save_nrf54l15dk.hex`
- 대상: `nrf54l15dk/nrf54l15/cpuapp`
- NCS: v3.3.1
- 빌드: 성공
- FLASH: 331,112 B / 1,428 KB (22.64%)
- RAM: 69,028 B / 188 KB (35.86%)

## 사용 순서

1. 새 HEX를 보드에 Write합니다.
2. nRF Serial Terminal, PuTTY 등 COM 포트를 사용하는 프로그램을 모두 닫습니다.
3. PowerShell에서 이 폴더로 이동합니다.
4. `COM7`을 실제 DK UART 포트로 바꿔 실행합니다.

```powershell
powershell -ExecutionPolicy Bypass -File .\capture_phonecs_stop_save.ps1 -Port COM7
```

정상 시작 메시지:

```text
Capturing COM7 at 115200 baud
Press Enter to stop the board measurement and save the CSV.
```

수집기가 거리값을 콘솔에 표시하면서 각 행을 CSV에 즉시 기록합니다.

## 측정 중지 및 저장

PowerShell 수집기 창에서 `Enter`를 한 번 누릅니다.

1. PC가 UART로 `STOP`을 전송합니다.
2. 보드가 `bt_le_cs_procedure_enable(... DISABLED)`로 CS Procedure를 중지합니다.
3. 보드가 `PHONECS_STOPPED` 응답을 전송합니다.
4. 수집기가 `CS stopped: INITIATOR`와 `전체 세션 중지` 이벤트를 기록합니다.
5. CSV 파일을 닫고 저장 경로를 표시합니다.

정상 종료 메시지:

```text
STOP sent; waiting for board acknowledgement...
Board acknowledged CS stop.
Saved ... distance rows to ..._CS_INITIATOR.csv
```

동기 중지에는 `Ctrl+C`가 아니라 반드시 `Enter`를 사용합니다. 새 측정을 시작하려면
수집기를 다시 실행한 뒤 보드를 리셋합니다.

## CSV 형식

제공된 PhoneCS 참고 파일과 같은 10열 구조, 전체 데이터 인용, 거리 소수점 6자리,
최근 5개 원시 거리 이동평균을 사용합니다.

```text
timestamp,elapsed_ms,role,record_type,source,event,raw_distance_m,smoothed_distance_m,sample_count,peer_address
```

## 재생 테스트

```powershell
powershell -ExecutionPolicy Bypass -File .\capture_phonecs_stop_save.ps1 -ReplayInputPath .\sample_uart_stop.log
```

테스트 결과 `20260804_152002_721_CS_INITIATOR.csv`에 거리 7행과 보드 중지 확인
이벤트가 정상 기록되었습니다.

## 무결성

파일별 SHA-256은 `SHA256SUMS.txt`에 있습니다.
