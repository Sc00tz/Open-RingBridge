# Health Ring BLE protocol

Derived by static analysis of `com.yucheng.healthring` 2.0.15 (versionCode 81).
Base APK SHA-256: `b3a4ff271395b3d9cd463068073a255c00294b197e93d088cef8bcd2ba95602c`.

**Tested against a real ring (`R01L C923`, firmware 1.13) on 2026-11-06, on and off a finger. See "Verified on the real ring". Everything else is still static analysis.**

Labels: **[code]** read directly from decompiled code. **[inferred]** a conclusion
drawn from the code, not stated by it. **[unknown]** not established; test it. **[observed]** seen on the real ring.

Unless noted, classes are under `com.yucheng.ycbtsdk`.

## GATT layout

Source: `core.CMD`, `gatt.BleHelper.mOnServicesDiscovered`. **[code]**

| Role | UUID |
|---|---|
| Main service | `be940000-7333-be46-b7ae-689e71722bd5` |
| Write commands (`UUID_C_1`), also has indications enabled | `be940001-7333-be46-b7ae-689e71722bd5` |
| Secondary write (`UUID_C_2`), used for dial/OTA commands | `be940002-7333-be46-b7ae-689e71722bd5` |
| Notify (`UUID_C_3`), enabled first | `be940003-7333-be46-b7ae-689e71722bd5` |
| JieLi service / write / notify | `0000ae00` / `0000ae01` / `0000ae02` (standard Bluetooth base UUID) |
| Nordic UART service / RX / TX | `6e400001` / `6e400002` / `6e400003` (`-b5a3-f393-e0a9-e50e24dcca9e`) |

- The app requests MTU 500, unless its MTU is already 185, and then enables
  indications through descriptor `00002902`. **[code]**
- **Write the indication value (`02 00`) to `00002902`, not the notification value (`01 00`).**
  When an Android phone wrote `01 00` to C1, the ring dropped the connection within about 35 ms
  (HCI reason 0x13). The same steps with `02 00` sync normally. **[seen, Pixel 9 Pro, 2026-11-19]**
- **Observed on a real ring** (`R01L C923`, 2026-11-06, from macOS, MTU reported as
  185). Services listed only, with no values read or written:

| Service | Characteristics (properties) |
|---|---|
| `be940000...` main | `be940001` (indicate, write, write-without-response), `be940002` (write-without-response), `be940003` (indicate) |
| `6e400001...` Nordic UART | `6e400002` (write, write-without-response), `6e400003` (notify) |
| `0000fee7` | `0000fec9` (read), `0000fea1` (read, indicate), `0000fea2` (read, indicate, write) |
| `0000180d` standard Heart Rate | `00002a37` (notify), `00002a38` (read) |
| `0000ae00` JieLi | `0000ae01` (write-without-response), `0000ae02` (notify) |

- The ring advertises services `180D` and `FEE7`, not the main service. It matches the
  app's scan filter through manufacturer data starting `11 78`.
- The JieLi service being present doesn't establish the chip scheme, so `0x021B` still
  has to be asked. **[unknown]**

## Frame format

Source: `core.YCBTClientImpl.sendData2Device`, `bleDataResponse`.

```
byte 0   group
byte 1   key
byte 2-3 total frame length, including this header and the CRC (little-endian)
byte 4.. payload
last 2   CRC-16 over everything before it
```

- The SDK's "data type" is `(group << 8) | key`, so `0x0506` is group 5, key 6. **[code]**
- Outgoing CRC is sent low byte first. **[code]**
- CRC is CRC-16/CCITT-FALSE (init `0xFFFF`, poly `0x1021`). **[inferred]** The
  implementation was ported to Python and gave the standard check value `0x29B1`
  for `"123456789"`.
- Incoming CRC is not validated by the SDK in the receive path. **[code]** On the real
  ring every reply checked out with the same CRC, low byte first, so a client can
  validate it. **[observed]**
- Long frames are split across writes and notifications at `MTU - 3` bytes, and the
  receiver reassembles them using the length field. **[code]**
- A one-byte payload with the top four bits set is an error for most groups: `FB`
  unsupported command, `FC` unsupported key, `FD` bad length, `FE` bad data,
  `FF` bad CRC. **[code]**

### Groups

Source: `core.CMD`. **[code]**

| Group | Name | Group | Name |
|---|---|---|---|
| 1 | Setting | 8 | Factory |
| 2 | Get | 9 | Dial |
| 3 | AppControl | 10 | OtaDevice |
| 4 | DevControl | 12 | SelfInspection |
| 5 | Health | 13 | Customize |
| 6 | Real | 14 | TestTool |
| 7 | Collect | 19 | CollectionTools |
| | | 126 | OtaUI |

### Test vectors

Computed with the ported CRC. **[inferred]**

| Command | Bytes |
|---|---|
| Get device info | `02 00 08 00 47 43 6f ec` |
| Get feature flags | `02 01 08 00 47 46 9b 16` |
| Get chip scheme | `02 1b 06 00 9c 70` |
| Start heart history | `05 06 06 00 83 20` |
| Acknowledge history OK | `05 80 07 00 00 f3 6a` |

## Connection sequence

Source: `YCBTClientImpl.bleStateResponse`, `packetSettingHandle`, `packetGetHandle`,
`BleHelper` GATT callback. **[code]**

1. Connect and discover services.
2. Enable indications on `C_3`, then on `C_1`.
3. The SDK's state becomes 9. It queues `0x0203` with `"GP"` (device name), but its
   own queue only allows `0x0100`, `0x0201`, `0x021B`, `0x0200` at this state, so
   that request appears to be dropped.
4. Send time, `0x0100`, 8-byte payload: year (2 bytes, little-endian), month, day,
   hour, minute, second, weekday (0 = Monday ... 6 = Sunday). Local time.
5. Send `0x0201` with `"GF"` (`47 46`): feature flags, 14+ bytes.
6. Send `0x021B` with an empty payload: chip scheme.
7. Send `0x0200` with `"GC"` (`47 43`): device info.
8. Scheme 3, 4 or 5 means JieLi: the app runs `RcspAuth` before ready. Any other
   scheme goes straight to ready (state 10).

Whether the **ring** requires this order, or only the SDK's queue does: **[unknown]**.

## Authentication and encryption

- Non-JieLi path: no authentication step in the connection sequence. **[code]**
- JieLi path: `com.jieli.jl_rcsp.impl.RcspAuth` runs, and on failure the SDK
  disconnects. **[code]** The APK also ships `libjl_auth.so`. This flow has not
  been traced.
- **The tested ring reports scheme 4 (JieLi) but answers every command tried on the
  main service with no authentication.** **[observed]** So the app's auth appears to
  guard the JieLi channel or the app's own state, not these commands. **[inferred]**
- Frame payloads go straight from `bleDataResponse` to the parsers with no decryption
  step in the path traced. **[code]**
- `utils.CryptUtil` has no incoming references. **[code]**
- The `isSupportUploadDataEncryption` feature flag exists, but what it controls has
  not been traced. **[unknown]**

## Device info: `0x0200` response

Source: `core.DataUnpack.unpackDeviceInfoData`. **[code]**

| Offset | Field |
|---|---|
| 0-1 | Device ID (little-endian) |
| 2 | Firmware minor |
| 3 | Firmware major |
| 4 | Battery state |
| 5 | Battery level |
| 6 | Bind state |
| 7 | Sync state |
| 8-19 | Version fields and hardware type (offset 18), only when the payload is 24+ bytes |

Battery level units and state values: **[unknown]**.

## Live data

- Toggle: `0x0309`, payload `[enable, type, 2]`. **[code]** Values of `type`: **[unknown]**.
- Live values arrive as group 6 frames: **[code]**

| Key | Data type | Payload |
|---|---|---|
| 0 | `0x0600` sport | steps, distance, calories (each 2 bytes, little-endian) |
| 1 | `0x0601` heart | byte 0: BPM |
| 2 | `0x0602` SpO2 | byte 0: value |
| 7 | `0x0607` respiration | byte 0: value |

- Start a measurement: `0x032F`, payload `[1, selector]`; stop with `[0, selector]`. The reply is
  `00` accepted or `01` refused. Frames then stream, and `0x040E [selector, 01]` marks completion.
  Selector values are not in the Java code; these came from trying 0-20 on a worn ring. **[observed]**

| Selector | Measures | Stream frame | Time to finish | Seen |
|---|---|---|---|---|
| 0 | Heart rate | `0x0601`, byte 0: BPM | ~17s | 100 BPM, confirmed correct by the wearer |
| 1 | Blood pressure | `0x0603`: systolic, diastolic, HR | ~16s | 119/78, HR 100; also stored in history |
| 2 | SpO2 | `0x0602`, byte 0 | ~63s | 99; also stored in history |
| 5 | Glucose | `0x060A` comprehensive: byte 20 raw value, byte 14 wearing state | ~17s | raw 72 |
| 10 | HRV | `0x0603`, byte 3 | ~16s | 18 |
| 12 | Stress | `0x0610`, byte 4 | ~8s | 6; also stored in body history |
| 4 | **[unknown]** accepted, no frames in 120s | | | |
| 3, 6-9, 11, 13-20 | Refused (`01`) | | | |

- `0x040E` status: `[selector, 01]` is success. `[00, 02]` arrived about 1s after an accepted heart start, three times out of
  three, with no heart frames, at a time when the ring had stopped storing automatic records. **[inferred]** `02` means
  it couldn't measure, possibly because it wasn't being worn; not confirmed.
- Glucose raw value: the app shows `raw / 10` with integer division. The unit is not stated in the
  code. **[inferred]** mmol/L, per the earlier project's notes; unverified. All blood pressure and
  glucose values are the ring's estimates.
- In history, a zero means "not measured": a combined record from a SpO2 measurement had zeros
  for heart rate and blood pressure. **[observed]**

## History sync

Source: `YCBTClientImpl.packetHealthHandle`, checked against JADX `simple` mode because
the normal decompile of its switch was misleading.

1. **Request:** send the type's data type with an empty payload, for example `0x0506`.
2. **Header:** a frame with the same key. **[code]** If the payload is longer than 9
   bytes: record count (2 bytes), total packets (4 bytes), total bytes (4 bytes),
   little-endian. Otherwise there is no data and the sync ends there.
3. **Chunks:** frames with the type's chunk key. Concatenate their payloads. **[code]**
4. **End:** key `0x80`: packet count, byte count, CRC (each 2 bytes, little-endian).
   CRC is over the concatenated data. **[code]**
5. **Acknowledge:** `0x0580` with `00` on success, `04` on CRC mismatch. **[code]**

| Data | Request | Chunk key |
|---|---|---|
| Steps/activity | `0x0502` | 17 |
| Sleep | `0x0504` | 19 |
| Heart rate | `0x0506` | 21 |
| Blood pressure | `0x0508` | 23 |
| Combined samples | `0x0509` | 24 |
| Workouts | `0x052D` | 46 |
| Lab-style metrics | `0x052F` | 48 |
| Body/stress indexes | `0x0533` | 52 |
| Wear status | `0x0566` | 103 |

The `*Ack` names in `CMD.KEY_Health` are misleading: those keys carry data.

**Does the `0x0580` acknowledgement make the ring delete or mark that data?
[unknown]** The SDK has separate delete commands, so it may not, but assume it could
until tested.

### Delete commands

`YCBTClient.deleteHealthHistoryData(type)` sends the data type below with payload `02`. **[code]**
Each call takes only a type: no date, index or count. So deleting looks partial by data type
but all-or-nothing within a type. **[inferred]** None of these has been sent to the ring.

| Code | Deletes | Code | Deletes |
|---|---|---|---|
| `0x0540` | Steps/activity | `0x0545` | Blood oxygen |
| `0x0541` | Sleep | `0x0547` | Temperature |
| `0x0542` | Heart rate | `0x054B` | Workouts |
| `0x0543` | Blood pressure | `0x054E` | Body/stress indexes |
| `0x0544` | `DeleteAll`, see below | | |

`DeleteAll` is ambiguous. The combined stream `0x0509` is named `Health_HistoryAll`, so `0x0544` may delete
only combined samples, or it may delete every type. **[unknown]** Treat it as deleting everything.

The per-record delete calls (`deleteHistoryListData`, keys 48-49) are for collected ECG/PPG
files, not this history. **[code]** Back up the raw bytes of every sync. Reading *without*
acknowledging is repeatable: a second heart read returned the same 295 records. **[observed]**

### Record layouts

- Timestamps are 4-byte little-endian seconds since 2000-01-01 (add `946684800` for a
  Unix-style value). The SDK then subtracts the phone's UTC offset, so the ring appears
  to store local time. **[inferred]**
- Heart rate, 6 bytes per record: timestamp (4), mode (1), BPM (1). **[code]**
- Blood pressure, 8 bytes: timestamp (4), mode (1), systolic (1), diastolic (1), unused (1). **[code]**
- Steps, 14 bytes: start (4), end (4), steps (2), distance (2), calories (2). **[code]**
- Combined, 20 bytes: timestamp (4), steps (2), then one byte each: HR, systolic, diastolic,
  SpO2, respiration, HRV, CVRR, temp integer, temp fraction, fat integer, fat fraction,
  glucose. **[code]**
- Body, 28 bytes: timestamp (4), integer/fraction pairs for load, HRV, stress, body and
  sympathetic (10; the SDK keeps both parts, and sibling records join them as text, so `4, 1` means 4.1 **[inferred]**), SDNN (2), VO2 max (1), pNN50 (1), RMSSD (2), LF (2), HF (2), LF/HF / 10 (1). **[code]**
- Sleep, variable: 20-byte session header: byte 0-1 unknown, length (2), start (4), end (4),
  deep count (2, `FFFF` = newer format), REM (2), deep (2), light (2). Then 8-byte segments:
  stage (1), start (4), seconds (3). Totals are seconds in the newer format, minutes in the
  older one. **[code]** Stages: `F1` deep, `F2` light, `F3` REM, `F4` awake, `F5` nap. **[code]**
  On the real ring each total equals the sum of its stage's segments for all 6 sessions. **[observed]**
- Wear status, 5 bytes: timestamp (4), status (1). **[code]**
- Combined bytes 4-5 are a running step counter, not steps per record. **[observed]**
- Sleep sessions: one night can be split into several sessions. Two nights began at exactly 20:01:09 and one at 20:31:09,
  and no session contained an `F4` awake segment. Whether those July start times were real bedtimes is
  **[unknown]** (not remembered).
- Night of 2026-11-06: one session, 01:13:39 to 07:55:52, which the wearer confirmed as about right for
  falling asleep and getting up. **[observed]** Deep 1h43m, light 3h48m, REM 1h11m, and no `F4` awake segment
  (the only 8 seconds not covered by a stage are gaps between segments). Brief wake-ups seem to go unrecorded, so awake time and efficiency read high. **[inferred]**
  No steps were stored during the session.

## Verified on the real ring

All **[observed]** on 2026-11-06 from macOS. Raw traffic is in `client/captures/`.

| Command | Result |
|---|---|
| `0x021B` chip scheme | `04`, JieLi |
| `0x0200` device info | Device ID 163, firmware 1.13, hardware type 1 |
| `0x0201` feature flags | 64 bytes; decoded below |
| `0x0203` device name | `R01L` |
| `0x0220` all live data | `FC`, unsupported key |
| History: steps, sleep, heart, blood, combined, body | Data returned; end-packet CRC correct on all |
| History: workouts | Header with no data |
| History: lab metrics, wear status | `FC`, unsupported key |
| `0x0309 [1,1,2]` live on | Accepted (`00`). Stream only seen during a measurement |
| `0x032F [1,0]` start measurement | Off finger or charging: `01`, the SDK's failure code. On finger: `00`, then `0x0601` heart frames about once a second |
| `0x040E` from the ring | Sent unprompted about 20s into the measurement, payload `00 01`. The SDK names it `MeasurementResult`; streaming then stopped. Payload meaning: **[inferred]** type, then success |
| `0x0100` set time | Accepted (`00`). The next stored records carried the correct date and time |
| Standard `2A37` heart rate | Notifies about every 1.3s. Off finger: value 0. On finger: real BPM, matching `0x0601`. Flags stayed `04` either way, so **the contact bit is not usable** |

Device info bytes 6, 10-13, 16 and 20-23 changed between reads, so the SDK's version labels for
those offsets look wrong. Byte 4 was `02` while byte 5 rose from 8 to 15 in about 3 minutes,
which suggests `02` means charging. It read `00` once off the charger, at level 25. **[inferred]**

Features reported on: steps, sleep, live data, firmware update, heart rate, blood pressure,
SpO2, HRV, glucose, stress, and measurement start for heart, blood pressure, SpO2, stress,
HRV and glucose. Not reported: ECG, temperature, respiration flag. The ring still sends
respiration values in combined records, and temperature there is a constant 0.15, so treat
it as unsupported. **[observed]**

**Automatic recording:** while worn, the ring stored a combined record about every 5 minutes without any command
(18 of 19 gaps on 2026-11-06 were 4.3-5.7 minutes; the other was 15.3). Steps are stored in 30-minute periods. **[observed]**

Stored records stopped at 2026-07-06 because the ring was not worn after that. New heart and combined
records appeared within minutes of wearing it and setting the time. A record appeared at 12:50:20, right
after the time sync, but whether the sync triggered it or the ring was already measuring: **[unknown]**.
BPM has not been checked against a reference device.

**Ring clock after a flat battery (2026-11-16):** the ring stopped recording at 2026-11-11 23:30 and a sync at
11-12 08:23 couldn't find it. After it was charged, everything it recorded was stamped 66 hours early, until a
sync set the clock: the last late record read 11-13 21:20:56, and the next record, 4 min 50 s after the sync,
read 11-16 15:25:46. Labels never ran backwards and no step period repeated, so the clock **stopped and resumed**
rather than jumped. **[observed]** That the battery ran flat is **[inferred]**. The ring has no clock-read command:
`0x020D` (history outline) and `0x0208` (device log) both answer `FC`. **[observed]** The client detects this
from the records (`app/clockfix.py`) and asks before moving them.

**History holds about a day of vitals:** each sync returned at most ~375 heart and ~382 combined records, about
31 hours at one per 5 minutes. With no sync from 11-09 09:50 to 11-16, heart and combined records from 11-09 10:00
to 11-11 16:55 were gone, while steps (back to June) and sleep sessions (back to 11-08) were still there. The
oldest records seem to be overwritten. **[inferred]** Sync at least once a day.

## Mac client notes

- macOS hides Bluetooth MAC addresses and gives each device a local UUID. **[inferred]**
  The client has to find the ring by name or advertised service, and any SDK logic
  that depends on the MAC address needs a replacement.
- CoreBluetooth manages MTU itself, so use the maximum write length it reports rather
  than requesting 500.
- The ring may only accept one connection at a time, so the phone app probably has to
  be disconnected first. **[inferred]**

## Workouts

- Start: `0x030C [1, type]`, then `0x0309 [1, 1, 2]`. Stop: `0x030C [0, type]`, then `0x0309 [0, 1, 2]`.
  **[code]** (`appRunMode`, `packetAppControlHandle`). The SDK ignores the reply's contents.
- On the charger, the ring replied `01` to both start and stop, as measurements do when refused. **[observed]**
  Replies on a worn ring haven't been seen, so `00` meaning "started" is **[inferred]**.
- The ring can report a change itself: `0x040C [state, type]`. **[code]** State values are **[unknown]**;
  `0` is assumed to mean stopped.
- Live counters: `0x0600`, steps, distance and calories. Whether they count from the workout's start or
  the whole day is **[unknown]**, so the client uses the change since the first frame.
- Whether heart rate streams during a workout, and whether the ring keeps recording while disconnected,
  are **[unknown]**. Each workout saves its raw frames to `data/raw/workout-*/frames.log`.
- Stored records (`0x052D`): 42 bytes, or 26 without GPS and `sportType`. u32 start, u32 end, u32 steps,
  u16 distance, u16 calories, u8 `sportMode`, u8 start method, u8 average HR, u32 seconds, u8 min HR,
  u8 max HR. **[code]** No real record has been decoded yet.
- **Type numbers are [unknown].** The app compiles them into Dart (`yc_sport_type.dart`). The client uses
  run 1, bike 3 and walk 8, recalled from the vendor's public SDK and not confirmed. The dashboard records
  your chosen type itself, so a wrong number would only mislabel the ring's own record.

## Remaining tests

1. Identify selector 4.
2. Confirm what `0x040E` status `02` means, by measuring with the ring on and off. A worn heart rate
   measurement through the dashboard finished normally on 2026-11-06 (101 BPM).
3. ~~Check sleep timestamps against real bedtimes.~~ Done 2026-11-07: matched on a new night.
4. Decide about the `0x0580` acknowledgement, ideally after a full raw backup.
5. Trace `isSupportUploadDataEncryption` only if a future firmware needs it.
6. Run a worn workout: start reply, heart stream, counter behavior, the stored record and its type number.
