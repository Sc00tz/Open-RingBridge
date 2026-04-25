# LivUp Smart Ring (R01L) — Reverse Engineered BLE Protocol

**Status**: Fully working as of 2026-04-13.  
Working metrics: Heart Rate, Blood Pressure, SpO2, Glucose (mmol/L + mg/dL), Stress, HRV, SDNN, Steps, Battery, Wearing State.

**FOR AI AGENTS — EASY MISTAKES:**
- `0x060A[11]` is **respiration rate**, NOT stress. Stress comes from body history (`0x0534`) or real-time ECG (`0x0610`).
- ALL history meta packets use `payload[6:8]` for byte count. Not `[4:6]`. This applies to `0x0509`, `0x0533`, and any other history command.
- `0x0613` (WearingStatus) only fires on state change. Do not expect it on connect.
- Real-time streaming (`0x060A` etc.) does NOT start until `AppControlReal START` (`0x0309`) is sent.

All information verified against **three sources**:
1. Decompiled official Android SDK (`CMD.java`, `DataUnpack.java`, `YCBTClientImpl.java`, `YcProductPluginHealthData.java`)
2. Live packet captures (`ring_debug.log`, `research/logs/04-12-2026-1304.log`)
3. Confirmed live operation of `best-script.py`

**FOR AI AGENTS**: Read this document in full before making any assumptions about packet IDs, byte layouts, or protocol flow. Many fields are counter-intuitive or differ from similar rings.

---

## Source Files (decompiled APK)

| File | Contents |
|---|---|
| `Ring Reverse Engineering/output_dir/sources/com/yucheng/ycbtsdk/core/CMD.java` | Protocol group/key constants and UUIDs |
| `Ring Reverse Engineering/output_dir/sources/com/yucheng/ycbtsdk/core/DataUnpack.java` | Byte-level decoders for every packet type |
| `Ring Reverse Engineering/output_dir/sources/com/yucheng/ycbtsdk/core/YCBTClientImpl.java` | BLE state machine, packet dispatch, history handling |
| `Ring Reverse Engineering/output_dir/sources/com/yucheng/ycbtsdk/YCBTClient.java` | Public SDK API |
| `Ring Reverse Engineering/output_dir/sources/com/example/yc_product_plugin/YcProductPluginHealthData.java` | Flutter plugin — shows exactly which history commands the official app uses |

---

## 1. Device Identification

- **Device name**: `R01L` (advertised in BLE scan)
- **Chip**: JieLi AC632N (identified via `0x021B` GetChipScheme response)
- **Firmware**: 1.13 (confirmed in live log)
- **Parent GATT Service UUID**: `be940000-7333-be46-b7ae-689e71722bd5`

---

## 2. BLE Characteristics (UUIDs)

| Name | UUID | Direction | Role |
|---|---|---|---|
| **C1** | `be940001-7333-be46-b7ae-689e71722bd5` | Bidirectional | Write commands to ring; ring sends responses, ACKs, and history meta |
| **C3** | `be940003-7333-be46-b7ae-689e71722bd5` | Ring → App | Ring pushes real-time sensor data and history bulk chunks |
| **HR Service** | `00002a37-0000-1000-8000-00805f9b34fb` | Ring → App | Standard BLE Heart Rate Measurement (runs continuously) |
| **JL Write** | `0000ae01-0000-1000-8000-00805f9b34fb` | App → Ring | JieLi RCSP auth write (only needed if ring was never paired) |
| **JL Notify** | `0000ae02-0000-1000-8000-00805f9b34fb` | Ring → App | JieLi RCSP auth notify (subscribe; non-fatal if absent) |
| **CCCD** | `00002902-0000-1000-8000-00805f9b34fb` | App → Ring | Client Characteristic Config Descriptor — enables INDICATE/NOTIFY |

### Subscription Setup

After connecting, subscribe to all notify/indicate characteristics by writing `[0x01, 0x00]` (notify) or `[0x02, 0x00]` (indicate) to the CCCD descriptor of each:
- C1 → subscribe INDICATE
- C3 → subscribe INDICATE
- HR Service → subscribe NOTIFY
- JL Notify → subscribe NOTIFY (best-effort; skip if characteristic absent)

The ring will not send any data until subscriptions are enabled.

---

## 3. Packet Structure

Every packet sent or received on C1 and C3 uses this envelope:

```
[type_hi] [type_lo] [len_lo] [len_hi] [payload...] [crc_lo] [crc_hi]
```

| Field | Bytes | Description |
|---|---|---|
| type | 0–1 | `(group << 8) \| key` — big-endian |
| len | 2–3 | Total packet length in bytes (header + payload + CRC), little-endian |
| payload | 4…(len-3) | Command or data bytes |
| CRC | last 2 | CRC-16 over bytes `0..(4 + payload_len - 1)`, little-endian |

**Minimum packet**: 6 bytes (4-byte header + 0-byte payload + 2-byte CRC).

### CRC-16 Algorithm

```python
def crc16(data: bytes, length: int) -> int:
    s = 0xFFFF
    for i in range(length):
        s = (((s << 8) & 0xFF00) | ((s >> 8) & 0xFF)) ^ data[i]
        s ^= (s & 0xFF) >> 4
        s ^= (s << 12) & 0xFFFF
        s ^= ((s & 0xFF) << 5) & 0xFFFF
    return s & 0xFFFF
```

---

## 4. Protocol Groups

`type = (group << 8) | key`

| Group | Value | Hex Prefix | Purpose |
|---|---|---|---|
| Setting | 1 | `0x01xx` | Write settings to ring (time, monitor intervals, etc.) |
| Get | 2 | `0x02xx` | Query ring state (device info, battery, steps) |
| AppControl | 3 | `0x03xx` | Control ring modes (real-time streaming, keepalive) |
| DevControl | 4 | `0x04xx` | Device-initiated notifications (data-ready alerts) |
| **Health** | **5** | **`0x05xx`** | **History data commands and responses** |
| **Real** | **6** | **`0x06xx`** | **Real-time sensor streaming packets** |

---

## 5. Handshake Sequence

**Confirmed working** (live logs from 2026-04-12 and 2026-04-13).  
Send each command in order with a **0.4 s delay** between sends.

```
1.  GetSupportFunction   0x0201  payload=[0x47, 0x46]
2.  SettingTime          0x0100  payload=make_ble_time()        ← sync ring clock
3.  GetChipScheme        0x021B  (no payload)
4.  GetDeviceInfo        0x0200  payload=[0x47, 0x43]           ← read battery + firmware
    ┌─ WAIT 3 seconds for JieLi chip authentication ──────────┐
    │  The ring's JieLi AC632N chip performs RCSP auth in      │
    │  the background. It requires the LivUp app to have       │
    │  paired at least once. No action needed; just wait.      │
    └──────────────────────────────────────────────────────────┘
5.  GetChipScheme        0x021B  (no payload)                   ← second poll post-auth
6.  GetDeviceName        0x0203  payload=[0x47, 0x50]
7.  GetDeviceInfo        0x0200  payload=[0x47, 0x43]
8.  GetDeviceInfo        0x0200  payload=[0x47, 0x43]
9.  AppControlReal STOP  0x0309  payload=[0x00, 0x00, 0x02, 0x90]
10. GetDeviceInfo        0x0200  payload=[0x47, 0x43]
11. Cmd0109              0x0109  payload=[0x00, 0x00, 0x31, 0x37]  ← purpose unknown
12. GetDeviceInfo        0x0200  payload=[0x47, 0x43]
13. SettingHeartMonitor  0x010C  payload=[0x01, 0x05]           ← background HR every 5 min
14. SettingSpO2Monitor   0x0126  payload=[0x01, 0x05]           ← background SpO2 every 5 min
15. GetDeviceInfo        0x0200  payload=[0x47, 0x43]
16. GetNowStep           0x020C  (no payload)
17. GetPowerStats        0x0225  (no payload)
18. Keepalive#0          0x032F  payload=[0x01, 0x00]
    ┌─ WAIT 1 second ──────────────────────────────────────────┐
19. [pull history — see §6]
20. AppControlReal START 0x0309  payload=[0x01, 0x00, 0x02, 0xA0]
```

### make_ble_time() — 8-byte time payload

```python
import time, calendar
now = time.localtime()
year = now.tm_year
# Ring day-of-week: Mon=0, Tue=1, ..., Sat=5, Sun=6
dow = (now.tm_wday + 1) % 7   # Python Mon=0, Sun=6 → shift so Sun becomes 6
payload = bytes([
    year & 0xFF, (year >> 8) & 0xFF,
    now.tm_mon, now.tm_mday,
    now.tm_hour, now.tm_min, now.tm_sec,
    dow
])
```

### Key handshake responses

**`0x0200` (DeviceInfo)** — ring sends this repeatedly:
| Byte | Field |
|---|---|
| [2] | Firmware minor version |
| [3] | Firmware major version |
| [5] | Battery % |

**`0x021B` (ChipScheme)** — single byte payload: `0x04` = JieLi AC632N.

**`0x020C` (NowStep)** response:
| Bytes | Field |
|---|---|
| [0..1] | Steps (LE) |
| [2..3] | Calories (LE) |
| [4..5] | Distance in metres (LE) |

**`0x0225` (PowerStats)** — battery charge status (e.g., end-of-charge %, current %).

---

## 6. Real-Time Monitoring (Group=6, `0x06xx`)

Real-time packets arrive on **C3** after `AppControlReal START` (`0x0309 [0x01,0x00,0x02,0xA0]`).

The HR Service characteristic streams heart rate independently of AppControlReal — it runs as soon as you subscribe.

### 0x060A — UploadComprehensive ✅ CONFIRMED WORKING
**SDK**: `DataUnpack.unpackRealComprehensiveData` (DataUnpack.java:2785)  
**Frequency**: ~1 per second  
**Source**: C3  
**Min payload**: 20 bytes

| Byte(s) | Field | Notes |
|---|---|---|
| [0..2] | Steps (3-byte LE) | |
| [3..4] | Distance m (2-byte LE) | |
| [5..6] | Calories (2-byte LE) | |
| **[7]** | **Heart Rate** | **bpm** |
| **[8]** | **SBP** | **Systolic mmHg** |
| **[9]** | **DBP** | **Diastolic mmHg** |
| **[10]** | **SpO2** | **Blood oxygen %** |
| **[11]** | **Respiration rate** | **breaths/min — NOT stress. Easy mistake.** |
| [12] | Temp integer | °C integer part |
| [13] | Temp decimal | °C decimal digit |
| **[14]** | **Wearing state** | **1=worn, 0=not worn** |
| [15] | Battery | % (often 0; use handshake 0x0200 for reliable battery) |
| [16..19] | PPI (4-byte LE) | Peak-to-peak interval ms |
| **[20]** | **Blood glucose raw** | **Divide by 10 → mmol/L** |
| [21..22] | Uric acid (2-byte LE) | |
| [23] | Cholesterol integer | |
| [24] | Cholesterol decimal | |

**Discard all-zero packets** — ring sends zeros during sensor warmup.  
**Temperature** (`[12..13]`): ring does not reliably populate this field; treat as unavailable.

---

### 0x0603 — UploadBlood (BP) ✅ CONFIRMED WORKING
**SDK**: `DataUnpack.unpackRealBloodData` (DataUnpack.java:2743)  
**Frequency**: ~1 s when ring is actively measuring BP  
**Source**: C3

| Byte | Field |
|---|---|
| [0] | SBP (Systolic mmHg) |
| [1] | DBP (Diastolic mmHg) |
| [2] | Heart Rate bpm |
| [3] | HRV |
| [4] | SpO2 (0 unless ring recently completed dedicated SpO2 measurement) |
| [5] | Temp integer |
| [6] | Temp decimal |

Discard packets where `[0] == 0` (still measuring).

---

### 0x0601 — UploadHeart (HR) ✅ CONFIRMED WORKING
**Source**: C3  
- `payload[0]` = Heart Rate bpm

The standard BLE HR Service (`0x2A37`) also provides HR independently of the YCBT protocol.

---

### 0x0602 — UploadBloodOxygen (SpO2)
**SDK**: `DataUnpack.unpackRealBloodOxygenData`  
**Source**: C3  
- `payload[0]` = SpO2 %

---

### 0x0610 — UploadBodyData (HRV/Stress, real-time)
**SDK**: `DataUnpack.unpackBodyData` (DataUnpack.java:459)  
**Source**: C3  
Sent during ECG emotional measurement (`0x032F [0x01,0x0C]` + `0x030B [0x01,0x00]`).

| Byte(s) | Field | Notes |
|---|---|---|
| [0] | loadIndex integer | |
| [1] | loadIndex decimal | |
| [2] | HRV integer | ms |
| [3] | HRV decimal | |
| [4] | pressure integer | stress integer |
| [5] | pressure decimal | **sentinel: 15 = still measuring** |
| [6] | body integer | |
| [7] | body decimal | |
| [8] | sympathetic integer | |
| [9] | sympathetic decimal | |
| [10..11] | SDNN (2-byte LE) | |
| [12] | VO2max | |
| [13] | pnn50 | |
| [14..15] | RMSSD (2-byte LE) | |
| [16..17] | LF (2-byte LE) | |
| [18..19] | HF (2-byte LE) | |
| [20] | LF/HF × 10 | divide by 10.0 |

Ignore packets where `payload[5] == 15` (measuring in progress).

---

### 0x0613 — UploadWearingStatus
**Source**: C3  
**Only sent when wearing state changes** (worn ↔ unworn). Not sent on connect.

| Byte(s) | Field |
|---|---|
| [0..3] | Timestamp (LE, seconds since 2001-01-01) |
| [4] | Status: 1=worn, 0=not worn |

Infer initial wearing state from any valid non-zero `0x0603` or `0x060A` packet.

---

### 0x0600 — UploadSport (Steps, real-time)
**Source**: C3 — steps/cal/distance activity update.

---

## 7. History Pull Protocol

History is pulled immediately after the handshake, before starting real-time streaming.

### Commands Used on R01L (confirmed working)

| Command | Datatype | C3 Data Type | Record Size | Contents |
|---|---|---|---|---|
| **GetAllHistory** | `0x0509` | `0x0518` | **20 bytes** | HR, SBP, DBP, SpO2, steps, HRV, temp, glucose — **the main history command** |
| **GetBodyHistory** | `0x0533` | `0x0534` | **28 bytes** | HRV, stress, SDNN, RMSSD, LF/HF, VO2max detail |
| ~~GetSpO2History~~ | ~~`0x051A`~~ | ~~`0x0522`~~ | 6 bytes | **Times out on R01L — do not use** |
| ~~GetBPHistory~~ | ~~`0x0508`~~ | ~~`0x0517`~~ | 8 bytes | Returns empty on R01L |
| ~~GetComprehensive~~ | ~~`0x052F`~~ | ~~`0x0530`~~ | 44 bytes | Returns "unsupported key" on R01L |

**Use `0x0509` for SpO2, BP, HR, and glucose history.** `0x0533` provides the detailed HRV breakdown.

### Full History Flow (per category)

```
App  →  C1: send history request (e.g. 0x0509) with empty payload
Ring →  C1: meta packet (same datatype, e.g. 0x0509)
            payload[0..1] = record count (LE)
            payload[2..5] = packet count (LE)
            payload[6..7] = total byte count (LE)   ← always [6:8], not [4:6]
            payload[8..9] = 0x0000
Ring →  C3: data chunks (e.g. 0x0518), each carrying N records
            (chunks arrive until total_bytes received)
Ring →  C1: 0x0580 transfer-done
            payload[0..1] = packet count (LE)
            payload[2..3] = total byte count (LE)
            payload[4..5] = CRC-16 of all accumulated C3 data
App  →  C1: 0x0580 ACK with payload [0x00] (CRC ok) or [0x04] (CRC fail)

App  →  C1: 0x0544 DeleteHistory (ONCE, after ALL categories done)
Ring →  C1: 0x0544 ACK
```

**Do NOT send chunk-level ACKs** during streaming. Only respond to `0x0580`.

**CRITICAL**: The `0x0580` ACK must carry payload `[0x00]`. Sending it with **empty payload** causes the ring to immediately retransmit the entire history stream from scratch. This was the root cause of post-sync SpO2 degradation in early versions.

### Meta Byte Layout (same for 0x0509 and 0x0533)

```python
num_records = payload[0] + (payload[1] << 8)
total_pkts  = payload[2] + (payload[3] << 8)
total_bytes = payload[6] + (payload[7] << 8)   # NOT payload[4:6]
```

If `total_bytes == 0` (2-byte response), no data stored — skip to next category.

---

### AllHistory Record Layout (0x0518, 20 bytes per record)

**Verified against `DataUnpack.java` case 9 (`Health_HistoryAll`).**  
**Confirmed working in live capture — provides SpO2, HR, BP, glucose history.**

| Bytes | Field | Notes |
|---|---|---|
| [0..3] | Timestamp (LE, seconds since 2001-01-01) | |
| [4..5] | Steps (2-byte LE) | |
| [6] | Heart Rate | bpm |
| [7] | SBP | Systolic mmHg |
| [8] | DBP | Diastolic mmHg |
| **[9]** | **SpO2** | **Blood oxygen %** |
| [10] | Respiration rate | breaths/min |
| [11] | HRV | ms |
| [12] | CVRR | |
| [13] | Temp integer | °C — ring populates inconsistently |
| [14] | Temp decimal | |
| [15] | Body fat integer | |
| [16] | Body fat decimal | |
| **[17]** | **Glucose raw** | **Divide by 10 → mmol/L; × 18.018 → mg/dL** |
| [18..19] | Unused | |

---

### Body Data Record Layout (0x0534, 28 bytes per record)

**Verified against `DataUnpack.java` case 51 (`HistoryBodyData`).**

| Bytes | Field | Notes |
|---|---|---|
| [0..3] | Timestamp (LE, seconds since 2001-01-01) | |
| [4] | Load index integer | HRV-based fitness load score |
| [5] | Load index decimal | |
| [6] | HRV integer | ms |
| [7] | HRV decimal | `hrv_ms = b[6] + b[7]/10.0` |
| [8] | Stress tens digit | `stress = b[8]*10 + b[9]` |
| [9] | Stress units digit | |
| [10] | Body score integer | |
| [11] | Body score decimal | |
| [12] | Sympathetic integer | |
| [13] | Sympathetic decimal | |
| [14..15] | SDNN (2-byte LE) | ms |
| [16] | VO2max | |
| [17] | pnn50 | |
| [18..19] | RMSSD (2-byte LE) | ms |
| [20..21] | LF (2-byte LE) | |
| [22..23] | HF (2-byte LE) | |
| [24] | LF/HF × 10 | divide by 10.0 |
| [25..27] | Unused | |

**Duplicate timestamps** are intentional — the ring buckets multiple background stress readings into the same 30-minute period boundary. Records with HRV=0 and SDNN=0 are quick background stress measurements without full ECG.

---

### Delete History (0x0544)

Send **once** after all history categories are complete.  
Ring ACKs with `0x0544`.  
**Only `0x0544` works on R01L.** Commands `0x054E` and `0x0543` do not exist.

---

## 8. Keepalive

Send `0x032F` with payload `[0x01, counter]` every **15 seconds**.  
Counter increments with each send (wraps at 255).

Ring responds with `0x032F` on C1:
| `payload[0]` | Meaning |
|---|---|
| `0x00` | All clear |
| `0x01` | Ring has pending history data → call `pull_history()` |

---

## 9. Timestamp Conversion

The ring uses seconds since **2001-01-01 00:00:00 UTC** (not Unix epoch).

```python
SEC_2001 = 946684800  # seconds between 1970-01-01 and 2001-01-01
unix_ts  = ring_ts + SEC_2001
datetime.fromtimestamp(unix_ts)
```

---

## 10. Error Codes

If the ring returns a **1-byte payload** with high nibble `0xFx`, it's an error:

| Code | Meaning |
|---|---|
| `0xFB` | Unsupported command |
| `0xFC` | Unsupported key |
| `0xFD` | Length error |
| `0xFE` | Data error |
| `0xFF` | CRC error |

---

## 11. Background Monitoring Settings

These commands enable the ring to collect data passively between syncs. Send during handshake.

| Command | Datatype | Payload | Effect |
|---|---|---|---|
| SettingHeartMonitor | `0x010C` | `[0x01, 0x05]` | Enable background HR, 5-min interval |
| SettingSpO2Monitor | `0x0126` | `[0x01, 0x05]` | Enable background SpO2, 5-min interval |
| SettingHRVMonitor | `0x0145` | `[0x01, 0x00, 0x00, 0x00, 0x00]` | Enable background HRV/stress — returns "unsupported key" on R01L firmware 1.13, non-fatal |

---

## 12. Confirmed Working vs. Not Supported on R01L (fw 1.13)

| Feature | Status | Source |
|---|---|---|
| Heart Rate (real-time) | ✅ Working | `0x0601`, HR Service, `0x060A[7]` |
| Blood Pressure (real-time) | ✅ Working | `0x0603[0..1]` |
| SpO2 (real-time) | ✅ Working | `0x060A[10]`, `0x0602[0]` |
| Glucose (real-time) | ✅ Working | `0x060A[20]` ÷ 10 |
| Wearing state | ✅ Working | `0x060A[14]`, `0x0613[4]` |
| Battery | ✅ Working | `0x0200[5]` during handshake |
| Steps | ✅ Working | `0x020C`, `0x060A[0..2]` |
| AllHistory (SpO2, HR, BP, glucose) | ✅ Working | `0x0509` → `0x0518`, 20-byte records |
| Body history (HRV, stress, SDNN) | ✅ Working | `0x0533` → `0x0534`, 28-byte records |
| Temperature | ⚠️ Unreliable | Field exists in `0x060A[12..13]` but ring does not reliably populate it |
| SpO2-only history (`0x051A`) | ❌ Times out | Use `0x0509` instead |
| Glucose/comprehensive history (`0x052F`) | ❌ Unsupported key | Glucose comes from `0x0509[17]` |
| BP-only history (`0x0508`) | ❌ Empty | BP comes from `0x0509[7..8]` |
| HRV monitor setting (`0x0145`) | ❌ Unsupported key | Non-fatal; skip |

---

## 13. Known Gotchas

1. **All history meta packets have byte count at `[6:8]`** — `payload[6] + (payload[7]<<8)`, not `[4:6]`.

2. **`0x0580` ACK must carry `[0x00]` payload** — empty payload causes the ring to immediately retransmit the entire history stream. The ring's `0x0580` packet carries 6 bytes: `[pkts_lo, pkts_hi, bytes_lo, bytes_hi, crc_lo, crc_hi]`. App must respond with `0x0580 + [0x00]` (CRC ok) or `[0x04]` (CRC fail).

3. **`0x060A` ≠ `0x0600`** — `0x060A` is UploadComprehensive (all sensors). `0x0600` is UploadSport (steps only).

4. **`0x0613` (WearingStatus) only fires on state change**, not on connect. Infer initial wearing state from `0x060A[14]` — but discard all-zero warmup packets (if all of steps/HR/SBP/SpO2 are zero, the ring is still warming up; do not update wearing state).

5. **Battery from `0x0200` only** — `0x060A[15]` is usually 0. Use the handshake response.

6. **SpO2 in `0x0603[4]`** is 0 unless the ring has recently completed a dedicated SpO2 measurement. Not continuously measured.

7. **Duplicate timestamps in `0x0534` body records** are normal — the ring buckets multiple readings into 30-minute period boundaries.

8. **JieLi auth requires prior LivUp pairing** — the ring must have been paired with the official LivUp app at least once. The JieLi RCSP handshake happens passively in the background during the 3-second sleep after the first `0x0200`. No explicit action needed.

9. **`0x0309` START/STOP for real-time** — STOP=`[0x00,0x00,0x02,0x90]`, START=`[0x01,0x00,0x02,0xA0]`. Send STOP before history pull; send START after. Real-time packets stop arriving during history pull.

10. **No chunk-level ACKs** during history streaming — only the final `0x0580` gets an ACK.

11. **`DeleteHistory` is `0x0544` only**, sent once after all categories. Never per-category.
