# LivUp Smart Ring (R01L) — Reverse Engineered BLE Protocol

**Status**: Fully working as of 2026-04-13.  
Working metrics: Heart Rate, Blood Pressure, SpO2, Glucose (mmol/L + mg/dL), Stress, HRV, SDNN, Steps, Battery, Wearing State.

**FOR AI AGENTS — EASY MISTAKES:**
- `0x060A[11]` is **respiration rate**, NOT stress. Stress comes from real-time ECG (`0x0610`).
- History: BOTH the per-category commands (`0x0502` Sport / `0x0506` Heart / `0x0508` Blood) AND the combined `0x0509` AllHistory / `0x0533` BodyHistory commands return real data on this R01L (verified via live capture 2026-06-08). An earlier revision of this doc wrongly called `0x0509`/`0x0533` unsupported — they are NOT; see §7.
- **Sleep is the exception:** the dedicated Sleep command `0x0504` returns an empty `[0x00,0x00]` meta on this ring — i.e. NO sleep-session data — even after a full night's wear. Where (or whether) this firmware exposes sleep staging is currently UNKNOWN; do not assume `0x0504`→`0x0513` works. See §7.
- Delete each category (`0x0540`–`0x0543`) only AFTER its records are confirmed saved locally. The ring does NOT auto-overwrite — without deletes its flash fills and recording stops (the official app deletes after every pull; confirmed by HCI capture). Deleting before a confirmed save risks loss — gate it.
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
deleted from the ring — but **only after its records are confirmed saved locally**.

> **DELETE-AFTER-CONFIRMED-SAVE.** The official app sends a per-category delete
> (`0x0540`–`0x0543`) right after pulling each category (confirmed by live HCI capture)
> — this is REQUIRED because the ring has limited flash and does NOT auto-overwrite
> oldest records; without deletes its storage fills and it stops recording. The danger
> is deleting *before* the data is safely stored. RingBridge resolves both: it decodes
> and writes each category to the local DB first, and only sends the delete on a
> confirmed save (skipped if decode/store throws, so the data stays on the ring for the
> next sync). Re-pulls are idempotent — the local DB dedupes on `(timestamp, type)`.
>
> History (this section) — earlier revisions of this doc swung between "always delete"
> (lost a full day of data) and "never delete" (filled the ring, stopped recording).
> Both were wrong; the gated approach above is correct.

### What this R01L actually returns (verified by live capture, 2026-06-08)

Every command below was probed against the physical ring after a full day + night of
wear. Results corrected a previous version of this doc that had several claims backwards:

| Category | Request | C3 Data Type | Record Size | Status on R01L |
|---|---|---|---|---|
| **Sport** | `0x0502` | `0x0511` | 14 bytes | ✅ returns data |
| **Heart** | `0x0506` | `0x0515` | 6 bytes | ✅ returns data |
| **Blood** | `0x0508` | `0x0517` | 8 bytes | ✅ returns data |
| **AllHistory** | `0x0509` | `0x0518` | 20 bytes | ✅ **returns data** — ~2.5 KB / 5-min resolution HR·BP·SpO₂·resp·glucose. (Previously mislabeled "unsupported".) |
| **BodyHistory** | `0x0533` | `0x0534` | 28 bytes | ✅ **returns data** — ~1.9 KB HRV/stress body records. (Previously mislabeled "unsupported".) |
| **Sleep** | `0x0504` | `0x0513` | — | ❌ **returns empty `[0x00,0x00]` meta** even after a full night. This ring does not expose dedicated sleep-session history. See note below. |

> **Sleep is unresolved on this firmware.** `0x0504` returns no data. It is possible
> sleep staging is embedded in the `0x0509` AllHistory stream (some record bytes are
> still unidentified) or that this ring simply does not compute sleep stages. The
> `0x0513` sleep-session layout documented below is from the SDK / `sleep-sync.log` and
> is **NOT confirmed against this R01L** — treat it as unverified until a non-empty
> `0x0504`/`0x0513` response is actually observed.

> **Tested empty / no response on R01L:** `0x0507`, `0x051D`, `0x0512` (candidate sleep
> commands), and the global `0x0544` DeleteAll.

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

### AllHistory Record Layout (0x0518, 20 bytes per record) — ✅ confirmed live

**Confirmed by live capture 2026-06-08: ~2.5 KB returned, one record per 5 minutes.**
Field offsets below are validated against decoded live records (HR 91, BP 120/80,
SpO₂ 98, resp 18 — all sane). This is the densest history this ring provides.

| Bytes | Field | Notes |
|---|---|---|
| [0..3] | Timestamp (LE, seconds since 2001-01-01) | |
| [4..5] | Steps (2-byte LE) | |
| [6] | Heart Rate | bpm |
| [7] | SBP | Systolic mmHg |
| [8] | DBP | Diastolic mmHg |
| [9] | SpO₂ | % |
| [10] | Respiration rate | brpm |
| [11] | HRV | ms |
| [13] | Temperature int | °C — usually 0 on this ring |
| [14] | sentinel `0x0F` observed on every record | meaning unconfirmed |
| [17] | Blood glucose raw | ÷10 → mmol/L |

> Bytes [12], [15], [16], [18..19] are not yet identified. **Sleep staging may live
> here** — investigation pending against an overnight capture.

---

### BodyData Record Layout (0x0534, 28 bytes per record) — ✅ confirmed live

**Confirmed by live capture 2026-06-08: ~1.9 KB returned (HRV / stress / autonomic).**
See §5b layout in `YCBT_Protocol_Reference.md` for the field map; offsets there matched
the live records.

---

### Sleep Record Layout (0x0513, variable length) — ⚠️ UNVERIFIED on this R01L

> **This ring returned NO sleep data (`0x0504` → empty `[0x00,0x00]`) in live testing
> on 2026-06-08, even after a full night's wear.** The layout below is from the SDK and
> an older `sleep-sync.log` capture (possibly a different device/firmware). It has NOT
> been confirmed against this R01L. Keep it as a reference, but do not rely on it until
> a non-empty `0x0513` response is actually observed.
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
| Heart history | ✅ Working | `0x0506` → `0x0515`, 6-byte records |
| Blood history (BP) | ✅ Working | `0x0508` → `0x0517`, 8-byte records |
| **AllHistory** (`0x0509` → `0x0518`) | ✅ **Working** (live-verified 2026-06-08) | ~2.5 KB, 5-min HR·BP·SpO₂·resp·glucose. Earlier "not supported" claim was wrong. |
| **BodyHistory** (`0x0533` → `0x0534`) | ✅ **Working** (live-verified 2026-06-08) | ~1.9 KB HRV/stress records. Earlier "not supported" claim was wrong. |
| **Sleep history** (`0x0504` → `0x0513`) | ❌ **Empty on this ring** | Returns `[0x00,0x00]` even after a full night. Sleep staging location unknown — see §7. |
| Temperature | ⚠️ Unreliable | Field exists in `0x060A[12..13]` but ring does not reliably populate it |
| HRV monitor setting (`0x0145`) | ❌ Unsupported key | Non-fatal; skip |

---

## 13. Known Gotchas

1. **All history meta packets have byte count at `[6:8]`** — `payload[6] + (payload[7]<<8)`, not `[4:6]`.

2. **`0x0580` ACK must carry `[0x00]` payload** — empty payload causes the ring to immediately retransmit the entire history stream. The ring's `0x0580` packet carries 6 bytes: `[pkts_lo, pkts_hi, bytes_lo, bytes_hi, crc_lo, crc_hi]`. App must respond with `0x0580 + [0x00]` (CRC ok) or `[0x04]` (CRC fail).

3. **`0x060A` ≠ `0x0600`** — `0x060A` is UploadComprehensive (all sensors). `0x0600` is UploadSport (steps only).

4. **`0x0613` (WearingStatus) only fires on state change**, not on connect. Infer initial wearing state from `0x060A[14]` — but discard all-zero warmup packets (if all of steps/HR/SBP/SpO2 are zero, the ring is still warming up; do not update wearing state).

5. **Battery from `0x0200` only** — `0x060A[15]` is usually 0. Use the handshake response.

6. **SpO2 in `0x0603[4]`** is 0 unless the ring has recently completed a dedicated SpO2 measurement. Not continuously measured.

7. **`0x0533` BodyHistory DOES return HRV/stress on this ring** (~1.9 KB, live-verified 2026-06-08) — contrary to an earlier claim here. Real-time ECG (`0x0610`) is an additional source, not the only one.

8. **JieLi auth requires prior LivUp pairing AND a JL_NOTIFY subscription** — the ring must have been paired with the official LivUp app at least once, and the app must subscribe to JL Notify (`0xae02`). The RCSP handshake completes passively over that characteristic during the 3-second sleep after the first `0x0200`. **If JL_NOTIFY is not subscribed, the ring sends no data at all** — no real-time packets, no history (confirmed via live capture: subscribing C1+C3 alone yielded zero packets; adding JL_NOTIFY made the ring respond).

9. **`0x0309` START/STOP for real-time** — STOP=`[0x00,0x00,0x02,0x90]`, START=`[0x01,0x00,0x02,0xA0]`. Send STOP before history pull; send START after. Real-time packets stop arriving during history pull.

10. **No chunk-level ACKs** during history streaming — only the final `0x0580` gets an ACK.

11. **Delete history only after a confirmed local save.** Per-category deletes (Sport=`0x0540`, Sleep=`0x0541`, Heart=`0x0542`, Blood=`0x0543`, payload `[0x02]`; global DeleteAll `0x0544`) are REQUIRED to free the ring's flash — it does not auto-overwrite, so skipping deletes fills storage and stops recording. The official app deletes after every pull. RingBridge deletes too, but only once the category's records are confirmed written to the local DB (so a decode/store failure leaves the data on the ring to retry). Never delete before the save is confirmed.
