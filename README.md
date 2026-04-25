# BioLocal Ring Bridge

> **Fully local health data collection for the LivUp R01L smart ring — no cloud, no subscription, no LivUp app required after initial pairing.**

BioLocal Ring Bridge reverse-engineers the YCBT BLE protocol used by the LivUp R01L (model C923) to stream all sensor data directly to your own infrastructure. Data stays on your hardware and flows into InfluxDB / Grafana / Home Assistant.

---

## Screenshots

<table>
  <tr>
    <td align="center"><b>Home</b></td>
    <td align="center"><b>Vitals</b></td>
    <td align="center"><b>Activity</b></td>
  </tr>
  <tr>
    <td><img src="Screenshots/Screenshot_20260415-192316.png" width="220"/></td>
    <td><img src="Screenshots/Screenshot_20260415-192326.png" width="220"/></td>
    <td><img src="Screenshots/Screenshot_20260415-192334.png" width="220"/></td>
  </tr>
  <tr>
    <td align="center"><b>History</b></td>
    <td align="center"><b>Heart Rate Chart</b></td>
    <td align="center"><b>SpO₂ Chart</b></td>
  </tr>
  <tr>
    <td><img src="Screenshots/Screenshot_20260415-192353.png" width="220"/></td>
    <td><img src="Screenshots/Screenshot_20260415-192401.png" width="220"/></td>
    <td><img src="Screenshots/Screenshot_20260415-192410.png" width="220"/></td>
  </tr>
</table>

---

## What It Captures

| Metric | Real-time | History | Notes |
|---|:---:|:---:|---|
| Heart Rate | ✅ | ✅ | `0x060A`, `0x0601` |
| Blood Pressure (sys/dia) | ✅ | ✅ | `0x0603`, `0x060A` |
| SpO₂ | ✅ | ✅ | `0x060A`, `0x0602` |
| Blood Glucose | ✅ | ✅ | `0x060A[20]`, `0x0518` — mmol/L |
| HRV / SDNN | ✅ | ✅ | Triggered emotional measurement → `0x0610` |
| Stress | ✅ | ✅ | Body history `0x0534`, real-time ECG `0x0610` |
| Respiratory Rate | ✅ | — | `0x060A[11]`, realKey `0x07` mode |
| Steps / Calories / Distance | ✅ | ✅ | `0x060A`, `0x0514` |
| Sleep Stages | — | ✅ | `0x0504` — Deep / Light / REM / Nap / Wake |
| Battery | ✅ | — | Handshake `0x0200` |
| Wearing State | ✅ | — | `0x060A[14]`, `0x0613` |

---

## Project Structure

```
├── RingBridge/                   Android app (Kotlin, Nordic BLE Library)
│   └── app/src/main/java/dev/ringbridge/
│       ├── RingBleManager.kt     BLE transport — GATT, subscriptions, write queue
│       ├── RingProtocol.kt       Protocol: packet framing, CRC, all decoders
│       ├── RingService.kt        Foreground service: handshake, history, streaming
│       ├── ServerPublisher.kt    Queued HTTP sync to RingBridgeServer
│       ├── RingDatabase.kt       Room DB: SensorReading + SleepSession tables
│       ├── HomeFragment.kt       Status + Readiness gauge
│       ├── VitalsFragment.kt     All health metric cards
│       ├── ActivityFragment.kt   Steps + Sleep
│       └── HistoryFragment.kt    Metric drill-down list
│
├── RingBridgeServer/             Python/FastAPI server (Docker)
│   ├── app/main.py               REST API entry point
│   ├── app/routers/              /api/v1/readings, /api/v1/sleep endpoints
│   ├── app/forwarder.py          InfluxDB line-protocol writer
│   ├── docker-compose.yml
│   └── Dockerfile
│
├── best-script.py                Python/Bleak test script — BLE terminal dashboard
├── Ring_Protocol_Documentation.md  Authoritative byte-level protocol reference
├── YCBT_Protocol_Reference.md    SDK-level reference (from APK reverse engineering)
└── research/                     Decompiled APK sources, BLE captures (gitignored)
```

---

## Android App

### Requirements
- Android 8.0+ (API 26)
- Bluetooth LE
- LivUp app paired to the ring at least once (establishes RCSP auth — no ongoing dependency)

### Build & Install

```bash
cd RingBridge
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** The Gradle wrapper requires Java 17. If your system default is older, prefix with `JAVA_HOME=/path/to/java17`.

### First Run

1. Tap **Start** on the Home tab
2. Grant Bluetooth permissions when prompted
3. Grant battery optimization exemption (keeps the service alive in the background)
4. The app scans, connects, performs the handshake, pulls ring history, then begins streaming

### App Tabs

| Tab | Contents |
|---|---|
| **Home** | Connection status · Readiness score gauge · HR / SpO₂ / Battery at-a-glance |
| **Vitals** | Heart Rate · SpO₂ · Blood Pressure · Battery · Blood Glucose · HRV · Stress · Resp Rate |
| **Activity** | Steps with goal progress bar · Last night's sleep breakdown |
| **History** | Tap any metric to open its time-series chart (1H / 6H / 24H / 7D) |

### Architecture

```
Ring (BLE INDICATE/NOTIFY)
        │
   RingBleManager          ← GATT transport, write queue
        │
   RingProtocol            ← Packet framing [type_hi][type_lo][len][payload...][crc16]
        │
   RingService             ← Foreground service: handshake → history → streaming loop
        │
   ┌────┴────┐
   │         │
Room DB   ServerPublisher  ← Queues all readings; flushes on interval or Wi-Fi restore
(SQLite)      │
              │  HTTP POST /api/v1/readings
         RingBridgeServer
              │
           InfluxDB ──→ Grafana
           Home Assistant
```

---

## Server (RingBridgeServer)

A lightweight Python/FastAPI service that accepts readings from the Android app and writes them to InfluxDB.

### Quick Start

```bash
cd RingBridgeServer
cp .env.example .env          # set ADMIN_PASSWORD, REGISTRATION_KEY, INFLUX_* vars
docker-compose up -d
```

The server listens on port `8080` by default.

### Environment Variables

| Variable | Description |
|---|---|
| `ADMIN_PASSWORD` | Dashboard admin password |
| `REGISTRATION_KEY` | Key used by the app to register a device token |
| `INFLUX_URL` | InfluxDB URL (e.g. `http://influxdb:8086`) |
| `INFLUX_TOKEN` | InfluxDB auth token |
| `INFLUX_ORG` | InfluxDB org |
| `INFLUX_BUCKET` | InfluxDB bucket for ring data |

### API

| Endpoint | Method | Description |
|---|---|---|
| `/api/v1/readings` | POST | Batch ingest sensor readings |
| `/api/v1/sleep` | POST | Ingest sleep sessions |
| `/api/v1/device/register` | POST | Register a device and receive a bearer token |

The Android app authenticates with `Authorization: Bearer <token>` on every request. Unsynced readings are queued in the local Room database and retried automatically when connectivity is restored.

---

## Python Test Script

For quick BLE testing without the Android app:

```bash
pip install bleak
python best-script.py
```

Close the LivUp app first. The script scans for the ring, performs the full handshake, pulls history, then streams live data to a terminal dashboard. All readings are saved to `ring_data.db` (SQLite).

**Requirements:** Python 3.9+, macOS or Linux with Bluetooth LE support.

---

## Data Flow

```
LivUp R01L Ring
       │  BLE INDICATE/NOTIFY (YCBT protocol)
       ▼
RingBridge Android App
       │  SQLite (offline buffer, 30-day rolling window)
       │  HTTP POST (batched, Wi-Fi-aware)
       ▼
RingBridgeServer (Docker)
       │
       ├──▶ InfluxDB  ──▶ Grafana dashboards
       └──▶ Home Assistant (webhook / MQTT)
```

---

## Protocol Notes

The ring uses the **YCBT protocol** — a proprietary framing layer over BLE INDICATE/NOTIFY characteristics.

**Packet structure:**
```
[type_hi][type_lo][len_lo][len_hi][payload...][crc16_lo][crc16_hi]
```

**Key findings from reverse engineering:**

- `AppControlReal` (`0x0309`) with `realKey=0x0A` triggers comprehensive streaming (`0x060A`) including glucose and resp rate — `realKey=0x00` (Sport mode) does not
- All history meta packets use `payload[6:8]` for the byte count, not `[4:6]`
- `0x0580` ACK **must** carry `[0x00]` payload — an empty payload causes the ring to retransmit the entire history stream
- `0x060A[11]` is respiration rate, not stress; stress comes from body history (`0x0534`) or triggered ECG (`0x0610`)
- `0x0613` (WearingStatus) only fires on state changes, not on connect
- HRV is not passively streamed — it requires triggering an "emotional measurement" sequence: `EMOTIONAL_START` → `CONTROL_WAVE_START` → wait for `0x0610` response → `CONTROL_WAVE_STOP`
- Blood glucose at `0x060A[20]`, raw byte ÷ 10 = mmol/L

See [`Ring_Protocol_Documentation.md`](Ring_Protocol_Documentation.md) for the complete byte-level protocol reference.

---

## Device

| Field | Value |
|---|---|
| Model | LivUp R01L (C923) |
| Chip | JieLi AC632N |
| Firmware | v1.13 |
| BLE Protocol | YCBT SDK (`com.yucheng.ycbtsdk`) |
| Tested MAC prefix | `07:32:00:09:C9:23` |

---

## What Doesn't Work

- **Temperature** — field exists in the protocol but the ring does not populate it reliably (always returns `0xFC`)
- **VO2max** — body history records contain a VO2max field but all observed values are zero; not supported by this ring in practice

---

## Contributing

Pull requests welcome. If you have a different firmware version or a ring that produces different byte layouts, opening an issue with a BLE packet capture is the fastest path to support.

---

## Disclaimer

This project is the result of independent BLE reverse engineering. It is not affiliated with, endorsed by, or connected to LivUp or Yucheng Technology. Use at your own risk. Health metrics from consumer rings are estimates — do not use for medical decisions.
