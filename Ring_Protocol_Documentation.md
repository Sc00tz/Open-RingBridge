# LivUp Smart Ring (R01L) — Reverse Engineered BLE Protocol

**Status**: Fully working as of 2026-04-13.  
Working metrics: Heart Rate, Blood Pressure, SpO2, Glucose (mmol/L + mg/dL), Stress, HRV, SDNN, Steps, Battery, Wearing State.

**FOR AI AGENTS — EASY MISTAKES:**
- `0x060A[11]` is **respiration rate**, NOT stress. Stress comes from real-time ECG (`0x0610`).
- History uses **per-category** commands (`0x0502`/`0x0504`/`0x0506`/`0x0508`), each deleted right after pulling. The combined `0x0509`/`0x0533` commands are NOT supported by the R01L firmware — see §7.
- ALL history meta packets use `payload[6:8]` for byte count. Not `[4:6]`.
- `0x0613` (WearingStatus) only fires on state change. Do not expect it on connect.
- Real-time streaming (`0x060A` etc.) does NOT start until `AppControlReal START` (`0x0309`) is sent.
- Keepalive interval is **30 s** (see §8).

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
| **JL Notify** | `0000ae02-0000-1000-8000-00805f9b34fb` | Ring → App | JieLi RCSP auth notify. **Required on R01L** — without this subscription the auth handshake never completes and the ring sends no data (confirmed via live capture). |
| **CCCD** | `00002902-0000-1000-8000-00805f9b34fb` | App → Ring | Client Characteristic Config Descriptor — enables INDICATE/NOTIFY |

### Subscription Setup

After connecting, subscribe to all notify/indicate characteristics by writing `[0x01, 0x00]` (notify) or `[0x02, 0x00]` (indicate) to the CCCD descriptor of each:
- C1 → subscribe INDICATE
- C3 → subscribe INDICATE
- HR Service → subscribe NOTIFY
- JL Notify → subscribe NOTIFY (**required on R01L** — the ring stays silent until this is enabled, because the JieLi RCSP auth completes over this characteristic)

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

History is pulled after the handshake. Each category is requested, ACKed, then
**deleted on the ring immediately** before moving to the next category.

> **This is the current method used by the RingBridge Android app**, and it mirrors
> the per-category sequence the official LivUp app performs on its morning sync
> (confirmed from `sleep-sync.log`). Earlier revisions (and `best-script.py`) used the
> combined `0x0509` AllHistory / `0x0533` BodyHistory commands — those are **never
> issued by the official app and the R01L firmware does not support them reliably**.
> The per-category commands below are what works on this ring.

### Commands Used on R01L (confirmed working)

| Category | Request | C3 Data Type | Delete | Record Size | Contents |
|---|---|---|---|---|---|
| **Sport** | `0x0502` | `0x0511` | `0x0540` | **14 bytes** | Steps, distance, calories per activity window |
| **Sleep** | `0x0504` | `0x0513` | `0x0541` | variable | Sleep sessions with per-stage records |
| **Heart** | `0x0506` | `0x0515` | `0x0542` | **6 bytes** | Heart rate samples |
| **Blood** | `0x0508` | `0x0517` | `0x0543` | **8 bytes** | Systolic / diastolic samples |

Each delete command carries payload `[0x02]`.

> **Not used / unsupported on R01L:** the combined `0x0509` (AllHistory → `0x0518`)
> and `0x0533` (BodyHistory → `0x0534`) commands, and the global `0x0544` DeleteAll.
> The official app never sends these and the firmware returns no data for them.

### Full History Flow (per category)

```
App  →  C1: send history request (e.g. 0x0502) with empty payload
Ring →  C1: meta packet (same datatype, e.g. 0x0502)
            payload[6..7] = total byte count (LE)   ← always [6:8], not [4:6]
Ring →  C3: data chunks (e.g. 0x0511), each carrying N records
            (chunks arrive until total_bytes received)
Ring →  C1: 0x0580 transfer-done
            payload[0..1] = packet count (LE)
            payload[2..3] = total byte count (LE)
            payload[4..5] = CRC-16 of all accumulated C3 data
App  →  C1: 0x0580 ACK with payload [0x00] (CRC ok) or [0x04] (CRC fail)
App  →  C1: send this category's delete (e.g. 0x0540) with payload [0x02]
Ring →  C1: delete ACK
            (repeat for the next category)
```

**Do NOT send chunk-level ACKs** during streaming. Only respond to `0x0580`.

**CRITICAL**: The `0x0580` ACK must carry payload `[0x00]`. Sending it with **empty payload** causes the ring to immediately retransmit the entire history stream from scratch. This was the root cause of post-sync SpO2 degradation in early versions.

### Meta Byte Layout (all history categories)

```python
total_bytes = payload[6] + (payload[7] << 8)   # NOT payload[4:6]
```

If the meta payload is empty, 2 bytes (`[0x00,0x00]`), or a 1-byte error with high
nibble `0xF` (e.g. unsupported), the category has no data — skip to the next one.

---

### Sport Record Layout (0x0511, 14 bytes per record)

**Verified against `DataUnpack.java` case 2 (`Health_HistorySport`).**

| Bytes | Field | Notes |
|---|---|---|
| [0..3] | Start time (LE, seconds since 2001-01-01) | |
| [4..7] | End time (LE, seconds since 2001-01-01) | |
| [8..9] | Steps (2-byte LE) | |
| [10..11] | Distance (2-byte LE) | metres |
| [12..13] | Calories (2-byte LE) | kcal |

---

### Heart Record Layout (0x0515, 6 bytes per record)

**Verified against `DataUnpack.java` case 6 (`Health_HistoryHeart`).**

| Bytes | Field | Notes |
|---|---|---|
| [0..3] | Timestamp (LE, seconds since 2001-01-01) | |
| [4] | Mode | Measurement mode (not persisted) |
| [5] | Heart Rate | bpm |

---

### Blood Record Layout (0x0517, 8 bytes per record)

**Verified against `DataUnpack.java` case 8 (`Health_HistoryBlood`).**

| Bytes | Field | Notes |
|---|---|---|
| [0..3] | Timestamp (LE, seconds since 2001-01-01) | |
| [4] | isInflated | 1 = cuff-style inflated measurement |
| [5] | SBP | Systolic mmHg |
| [6] | DBP | Diastolic mmHg |
| [7] | Unused | |

---

### Sleep Record Layout (0x0513, variable length)

**Verified against `DataUnpack.java` (HistorySleep) and live `sleep-sync.log`.**
The payload is one or more concatenated sessions. Each session is a 20-byte header
followed by 8-byte stage records.

**Session header (20 bytes):**

| Bytes | Field | Notes |
|---|---|---|
| [0] | Marker | always `0x01` |
| [1] | Marker | always `0x01` |
| [2..3] | totalLen (2-byte LE) | TOTAL session length **including** this 20-byte header; stage bytes = totalLen − 20 |
| [4..7] | Sleep start time (LE, seconds since 2001-01-01) | |
| [8..11] | Sleep end time (LE, seconds since 2001-01-01) | |
| [12..13] | deepSleepCount (2-byte LE) | `0xFFFF` signals "new sleep protocol" |
| [14..15] | lightSleepLen (2-byte LE) | old-protocol fallback only |
| [16..17] | deepSleepLen (2-byte LE) | old-protocol fallback only |
| [18..19] | remSleepLen (2-byte LE) | old-protocol fallback only |

**Stage records (8 bytes each), `(totalLen − 20) / 8` of them:**

| Bytes | Field | Notes |
|---|---|---|
| [0] | Stage type | `0xF1`=Deep, `0xF2`=Light, `0xF3`=REM, `0xF4`=Awake, `0xF5`=Nap |
| [1..4] | Stage start (LE, seconds since 2001-01-01) | |
| [5..7] | Duration (3-byte LE) | seconds |

Stage records are the authoritative source for stage totals; sum durations per type.
Advance by `totalLen` per session to reach the next concatenated session.

---

## 8. Keepalive

Send `0x032F` with payload `[0x01, counter]` every **30 seconds**.  
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
| Sport history (steps/distance/calories) | ✅ Working | `0x0502` → `0x0511`, 14-byte records |
| Sleep history (stages) | ✅ Working | `0x0504` → `0x0513`, variable-length sessions |
| Heart history | ✅ Working | `0x0506` → `0x0515`, 6-byte records |
| Blood history (BP) | ✅ Working | `0x0508` → `0x0517`, 8-byte records |
| Temperature | ⚠️ Unreliable | Field exists in `0x060A[12..13]` but ring does not reliably populate it |
| AllHistory (`0x0509` → `0x0518`) | ❌ Not used | Firmware returns no data; use the per-category commands above |
| Body history (`0x0533` → `0x0534`) | ❌ Not used | Firmware returns no data; HRV/stress come from real-time `0x0610` |
| HRV monitor setting (`0x0145`) | ❌ Unsupported key | Non-fatal; skip |

---

## 13. Known Gotchas

1. **All history meta packets have byte count at `[6:8]`** — `payload[6] + (payload[7]<<8)`, not `[4:6]`.

2. **`0x0580` ACK must carry `[0x00]` payload** — empty payload causes the ring to immediately retransmit the entire history stream. The ring's `0x0580` packet carries 6 bytes: `[pkts_lo, pkts_hi, bytes_lo, bytes_hi, crc_lo, crc_hi]`. App must respond with `0x0580 + [0x00]` (CRC ok) or `[0x04]` (CRC fail).

3. **`0x060A` ≠ `0x0600`** — `0x060A` is UploadComprehensive (all sensors). `0x0600` is UploadSport (steps only).

4. **`0x0613` (WearingStatus) only fires on state change**, not on connect. Infer initial wearing state from `0x060A[14]` — but discard all-zero warmup packets (if all of steps/HR/SBP/SpO2 are zero, the ring is still warming up; do not update wearing state).

5. **Battery from `0x0200` only** — `0x060A[15]` is usually 0. Use the handshake response.

6. **SpO2 in `0x0603[4]`** is 0 unless the ring has recently completed a dedicated SpO2 measurement. Not continuously measured.

7. **Stress and HRV are not in passive history** — they come only from a triggered real-time ECG emotional measurement (`0x0610`). The R01L does not return `0x0533` body history.

8. **JieLi auth requires prior LivUp pairing AND a JL_NOTIFY subscription** — the ring must have been paired with the official LivUp app at least once, and the app must subscribe to JL Notify (`0xae02`). The RCSP handshake completes passively over that characteristic during the 3-second sleep after the first `0x0200`. **If JL_NOTIFY is not subscribed, the ring sends no data at all** — no real-time packets, no history (confirmed via live capture: subscribing C1+C3 alone yielded zero packets; adding JL_NOTIFY made the ring respond).

9. **`0x0309` START/STOP for real-time** — STOP=`[0x00,0x00,0x02,0x90]`, START=`[0x01,0x00,0x02,0xA0]`. Send STOP before history pull; send START after. Real-time packets stop arriving during history pull.

10. **No chunk-level ACKs** during history streaming — only the final `0x0580` gets an ACK.

11. **History delete is per-category**, sent right after each category is pulled: Sport=`0x0540`, Sleep=`0x0541`, Heart=`0x0542`, Blood=`0x0543`, each with payload `[0x02]`. The global `0x0544` DeleteAll is not used by the official app.
