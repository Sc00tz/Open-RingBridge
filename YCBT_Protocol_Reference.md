# YCBT Smart Ring — BLE Protocol Reference
> Derived from: ycinnovate.com SDK docs, `flutter_ycbtsdk` source, decompiled `com.yucheng.ycbtsdk` SDK (jadx), live packet captures on LivUp R01L (fw 1.13)

> **Scope:** This is an SDK-level reference — packet type IDs, capability codes, data keys, and general YCBT protocol structure. For the R01L-specific confirmed byte layouts and handshake sequence, see [`Ring_Protocol_Documentation.md`](Ring_Protocol_Documentation.md).

---

## 1. Transport Layer

- **BLE characteristics**: proprietary YCBT GATT service (not standard Heart Rate service)
- **Byte order**: Little-Endian throughout
- **Epoch base**: Timestamps are seconds since **2001-01-01 00:00:00 UTC** (add `978307200` for Unix epoch)

### Packet Format

```
[type_hi][type_lo][len_lo][len_hi][payload...][crc_lo][crc_hi]
```

| Field     | Size     | Notes                                  |
|-----------|----------|----------------------------------------|
| type      | 2 bytes  | Command / notification ID (big-endian) |
| length    | 2 bytes  | Payload byte count (little-endian)     |
| payload   | variable | Command-specific                       |
| CRC       | 2 bytes  | CRC-16 over type+length+payload        |

---

## 2. Response / Error Codes

Returned in the first payload byte of ACK packets:

| Code   | Meaning              |
|--------|----------------------|
| `0x00` | Success / OK         |
| `0xFB` | Unsupported command  |
| `0xFC` | Unsupported key      |
| `0xFD` | Length error         |
| `0xFE` | Data error           |
| `0xFF` | CRC error            |

---

## 3. Device Info Commands

| Command  | Direction    | Description                         |
|----------|--------------|-------------------------------------|
| `0x0200` | App → Ring   | Get basic device info               |
| `0x0203` | App → Ring   | Get device model name               |
| `0x0207` | App → Ring   | Get/set user profile                |
| `0x020C` | App → Ring   | Get current step count              |

Battery status byte values (returned in device info):

| Value  | State          |
|--------|----------------|
| `0x00` | Normal         |
| `0x01` | Low battery    |
| `0x02` | Charging       |
| `0x03` | Fully charged  |

Hardware type byte:

| Value | Type                |
|-------|---------------------|
| `0`   | Smartwatch / band   |
| `1`   | Smart ring          |
| `2`   | Touch-enabled ring  |

---

## 4. Real-Time Data (Ring → App, continuous ~1 Hz)

### 4a. `0x060A` — UploadComprehensive

The main real-time packet. Fires approximately once per second while the ring is worn.
Discard packets where all of bytes 0–10 are zero — the ring sends all-zero packets during sensor warmup.

| Byte(s) | Field          | Unit / Notes                        |
|---------|----------------|-------------------------------------|
| 0–2     | Steps          | uint24 LE — cumulative today        |
| 3       | Distance lo    | (distance, low byte)                |
| 4       | Distance hi    | (distance, high byte) — metres      |
| 5       | Calories lo    | (calories, low byte)                |
| 6       | Calories hi    | — kcal                              |
| 7       | Heart Rate     | uint8 — bpm (0 = no reading)        |
| 8       | Systolic BP    | uint8 — mmHg                        |
| 9       | Diastolic BP   | uint8 — mmHg                        |
| 10      | SpO₂           | uint8 — % (0 = no reading)          |
| 11      | Resp Rate      | uint8 — breaths/min                 |
| 12      | Temp int       | uint8 — °C integer part             |
| 13      | Temp frac      | uint8 — °C fractional part          |
| 14      | Wearing state  | `0x00` = not worn · `0x01` = worn   |
| 15      | Battery level  | uint8 — %                           |
| 16–19   | PPI            | uint32 — peak-to-peak interval ms   |
| 20      | Glucose raw    | uint8 — divide by 10 → mmol/L       |

### 4b. `0x0603` — UploadBlood

Fired after a discrete blood measurement completes (less frequent).

| Byte | Field        | Unit / Notes      |
|------|--------------|-------------------|
| 0    | Systolic BP  | uint8 — mmHg      |
| 1    | Diastolic BP | uint8 — mmHg      |
| 2    | Heart Rate   | uint8 — bpm       |
| 3    | HRV          | uint8 — ms        |
| 4    | SpO₂         | uint8 — %         |

---

## 5. History Sync Commands

History pull is a two-step handshake:

1. App sends the **request** command
2. Ring replies with the matching **response** command (may be chunked)
3. App sends **delete** command to clear synced data from ring storage

### 5a. AllHistory — Comprehensive daily snapshots

| Step     | Command  | Notes                              |
|----------|----------|------------------------------------|
| Request  | `0x0509` | No payload                         |
| Response | `0x0518` | Chunked 20-byte records (see below)|
| Delete   | `0x0519` | Clears records from ring           |

**Record layout (20 bytes each):**

| Byte(s) | Field        | Notes                              |
|---------|--------------|------------------------------------|
| 0–3     | Timestamp    | uint32 LE — ring epoch             |
| 4–5     | Steps        | uint16 LE                          |
| 6       | Heart Rate   | uint8 — bpm                        |
| 7       | Systolic BP  | uint8 — mmHg                       |
| 8       | Diastolic BP | uint8 — mmHg                       |
| 9       | SpO₂         | uint8 — %                          |
| 10      | Resp Rate    | uint8 — breaths/min                |
| 11      | HRV          | uint8 — ms                         |
| 12      | CVRR         | uint8                              |
| 13      | Temp int     | uint8 — °C integer part            |
| 14      | Temp frac    | uint8 — °C fractional part         |
| 15      | Body fat int | uint8                              |
| 16      | Body fat frac| uint8                              |
| 17      | Glucose raw  | uint8 ÷ 10 → mmol/L               |
| 18–19   | Reserved     |                                    |

### 5b. BodyHistory — Stress / HRV / autonomic nervous system

| Step     | Command  | Notes                              |
|----------|----------|------------------------------------|
| Request  | `0x0533` | No payload                         |
| Response | `0x0534` | Chunked 28-byte records            |
| Delete   | — (same as AllHistory delete) |                   |

**Record layout (28 bytes each):**

| Byte(s) | Field           | Notes                                    |
|---------|-----------------|------------------------------------------|
| 0–3     | Timestamp       | uint32 LE — ring epoch                   |
| 4       | Load index int  | HRV-based fitness load score             |
| 5       | Load index frac |                                          |
| 6       | HRV int         | Integer part of HRV ms                   |
| 7       | HRV frac        | `hrv_ms = b[6] + b[7] / 10.0`           |
| 8       | Stress tens     | Stress = `b[8] × 10 + b[9]`             |
| 9       | Stress units    |                                          |
| 10      | Body score int  | Overall body/recovery score              |
| 11      | Body score frac |                                          |
| 12      | Sympathetic int | Autonomic nervous system balance         |
| 13      | Sympathetic frac|                                          |
| 14–15   | SDNN            | uint16 LE — ms                           |
| 16      | VO₂max          | uint8                                    |
| 17      | pnn50           | uint8                                    |
| 18–19   | RMSSD           | uint16 LE — ms                           |
| 20–21   | LF              | uint16 LE                                |
| 22–23   | HF              | uint16 LE                                |
| 24      | LF/HF × 10     | divide by 10.0                           |
| 25–27   | Reserved        |                                          |

### 5c. SleepHistory

| Step     | Command  | Notes                                   |
|----------|----------|-----------------------------------------|
| Request  | `0x0504` | No payload                              |
| Response | `0x0513` | Variable-length sessions (see below)    |
| Delete   | `0x0541` | Payload `[0x02]`                        |

**Session header (20 bytes):**

| Byte(s) | Field            | Notes                          |
|---------|------------------|--------------------------------|
| 0–1     | Type             | Command echo                   |
| 2–3     | dataLength       | uint16 LE — total bytes for this session (including header) |
| 4–7     | startTime        | uint32 LE — ring epoch         |
| 8–11    | endTime          | uint32 LE — ring epoch         |
| 12–13   | deepSleepCount   | uint16 — number of deep stages |
| 14–15   | lightSleepCount  | uint16 — number of light stages|
| 16–17   | deepSleepTotal   | uint16 — seconds (fallback)    |
| 18–19   | lightSleepTotal  | uint16 — seconds (fallback)    |

**Stage records (8 bytes each), starting at byte 20:**

| Byte(s) | Field       | Notes                                   |
|---------|-------------|-----------------------------------------|
| 0       | Stage type  | See table below                         |
| 1–4     | Stage start | uint32 LE — ring epoch                  |
| 5–6     | Duration lo | uint16 LE — seconds (low 16 bits)       |
| 7       | Duration hi | uint8 — seconds (high 8 bits)           |

**Sleep stage type codes:**

| Code   | Stage      |
|--------|------------|
| `0xF1` | Deep sleep |
| `0xF2` | Light sleep|
| `0xF3` | REM sleep  |
| `0xF4` | Awake      |
| `0xF5` | Nap        |

> **Parsing note:** Use the stage records as the authoritative source for deep/light/REM/wake totals. The header fields `[16-19]` are pre-computed by the ring and may only include deep+light (no REM). Fall back to header values only when stage records are absent.

---

## 6. Measurement Type Codes

Used with `isSupportFunction()` to check device capabilities, and in some history response packets to indicate what kind of measurement was taken.

| Code   | Metric           |
|--------|------------------|
| `0x00` | Heart rate       |
| `0x01` | Blood pressure   |
| `0x02` | SpO₂            |
| `0x03` | Respiratory rate |
| `0x04` | Temperature      |
| `0x05` | Blood glucose    |
| `0x06` | Uric acid        |
| `0x07` | Blood ketones    |
| `0x08` | EDA              |
| `0x09` | Blood lipids     |
| `0x0A` | HRV              |
| `0x0B` | PPG              |
| `0x0D` | Stress           |

---

## 7. ECG (Not implemented in RingBridge)

ECG is streamed via `startEcgTest()` / `stopEcgTest()`.

- Sample rates: 125 Hz, 200 Hz, 250 Hz
- Waveform normalisation: `(raw ÷ 40 ÷ 3)` clamped to ±500
- Processed in blocks of 3 samples
- Beat classification uses standard MIT-BIH codes (0=normal, 5=PVC, 8=APC, etc.)
- HRV is derived from RR intervals by the on-device ECG algorithm

---

## 8. SDK Callback Model (Android)

The underlying `YCBTClient` fires these callbacks:

| Callback                        | Trigger                                    |
|---------------------------------|--------------------------------------------|
| `BleScanResponse.onScanResponse`| Device found during scan                   |
| `BleConnectResponse.onConnectResponse` | Connection state change             |
| `BleDataResponse.onDataResponse`| Response to an app-initiated command       |
| `BleRealDataResponse.onRealDataResponse` | Real-time ring-initiated packet   |
| `BleDeviceToAppDataResponse`    | Device-initiated non-real-time data        |

Connection state codes:

| Condition                     | State          |
|-------------------------------|----------------|
| code < `BLEState.Disconnecting` | Disconnected |
| code == `BLEState.Disconnecting` | Disconnecting|
| code == `BLEState.Connecting`  | Connecting    |
| code >= `BLEState.Connected`   | Connected     |

---

## 9. Data Keys (Android HashMap)

Real-time and history callbacks return a `HashMap<String, Any>`. Known keys:

| Key                  | Source         | Value                         |
|----------------------|----------------|-------------------------------|
| `heartValue`         | Real-time      | Int — bpm                     |
| `OOValue`            | Real-time      | Int — SpO₂ %                  |
| `SBPValue`           | Real-time      | Int — systolic mmHg           |
| `DBPValue`           | Real-time      | Int — diastolic mmHg          |
| `respiratoryRateValue` | Real-time    | Int — breaths/min             |
| `tempIntValue`       | Real-time      | Int — °C integer part         |
| `tempFloatValue`     | Real-time      | Int — °C fractional part      |
| `startTime`          | History        | Long — ms since Unix epoch    |
| `sportStep`          | Sport history  | Int — steps                   |
| `sportDistance`      | Sport history  | Int — metres                  |
| `sportCalorie`       | Sport history  | Int — kcal                    |
| `code`               | Real-time      | Int — data type code          |
| `dataType`           | Both           | Int — 1539 for real-time      |

---

## 10. Status on LivUp R01L fw 1.13

| Feature              | Status                                                      |
|----------------------|-------------------------------------------------------------|
| AllHistory (`0x0509` → `0x0518`) | ✅ Working — HR, BP, SpO₂, glucose, HRV |
| BodyHistory (`0x0533` → `0x0534`) | ✅ Working — stress, HRV, SDNN, VO₂max |
| Sleep history (`0x0504` → `0x0513`) | ⚠️ Implemented in RingBridge — needs device test |
| Real-time comprehensive (`0x060A`) | ✅ Working — all metrics ~1 Hz |
| Real-time BP (`0x0603`)  | ✅ Working — includes HRV at byte 3                |
| Real-time HRV/stress (`0x0610`) | ✅ Implemented — requires ECG emotional mode    |
| Wearing state (`0x0613`) | ✅ Working — fires on state change only           |
| Temperature          | ⚠️ Field exists in `0x060A[12..13]` and history — ring does not populate it |
| ECG streaming        | Not implemented                                             |
| Uric acid / ketones / lipids | Advanced variants — not supported on this ring      |
| Sport mode history   | GPS + HR zones — not implemented                            |
| `0x0109` purpose     | Sent during handshake with `{0x00,0x00,0x31,0x37}` — function unknown, required |
