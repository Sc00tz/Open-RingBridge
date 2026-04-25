package dev.ringbridge

import dev.ringbridge.db.SleepSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * All packet framing, parsing, and sensor decoding logic.
 * Pure Kotlin — no Android dependencies. Direct port of app.py.
 */
object RingProtocol {

    // ── GATT UUIDs ────────────────────────────────────────────────────────────

    const val YCBT_SERVICE = "be940000-7333-be46-b7ae-689e71722bd5"
    const val YCBT_C1      = "be940001-7333-be46-b7ae-689e71722bd5" // command channel (INDICATE)
    const val YCBT_C3      = "be940003-7333-be46-b7ae-689e71722bd5" // real-time push (INDICATE)
    const val JL_WRITE     = "0000ae01-0000-1000-8000-00805f9b34fb" // JieLi auth write
    const val JL_NOTIFY    = "0000ae02-0000-1000-8000-00805f9b34fb" // JieLi auth notify
    const val HR_CHAR      = "00002a37-0000-1000-8000-00805f9b34fb" // standard HR service

    const val CCCD_UUID    = "00002902-0000-1000-8000-00805f9b34fb"

    const val DEVICE_NAME  = "R01L"

    /** Seconds between Unix epoch (1970-01-01) and ring epoch (2001-01-01). */
    private const val SEC_2001 = 946684800L

    // ── CRC & packet framing ──────────────────────────────────────────────────

    fun crc16(data: ByteArray, length: Int): Int {
        var s = 0xFFFF
        for (i in 0 until length) {
            s = (((s shl 8) and 0xFF00) or ((s ushr 8) and 0xFF)) xor (data[i].toInt() and 0xFF)
            s = s xor ((s and 0xFF) ushr 4)
            s = s xor ((s shl 12) and 0xFFFF)
            s = s xor (((s and 0xFF) shl 5) and 0xFFFF)
        }
        return s and 0xFFFF
    }

    fun buildPacket(datatype: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val totalLen = payload.size + 6
        val pkt = ByteArray(totalLen)
        pkt[0] = ((datatype ushr 8) and 0xFF).toByte()
        pkt[1] = (datatype and 0xFF).toByte()
        pkt[2] = (totalLen and 0xFF).toByte()
        pkt[3] = ((totalLen ushr 8) and 0xFF).toByte()
        payload.copyInto(pkt, destinationOffset = 4)
        val crc = crc16(pkt, 4 + payload.size)
        pkt[4 + payload.size]     = (crc and 0xFF).toByte()
        pkt[4 + payload.size + 1] = ((crc ushr 8) and 0xFF).toByte()
        return pkt
    }

    fun makeBleTime(): ByteArray {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        // Convert Java DAY_OF_WEEK (Sun=1..Sat=7) to ring format (Mon=0..Sat=5, Sun=6)
        val jDay = cal.get(Calendar.DAY_OF_WEEK)
        val dow = if (jDay == Calendar.SUNDAY) 6 else jDay - 2
        return byteArrayOf(
            (year and 0xFF).toByte(), ((year ushr 8) and 0xFF).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(),
            dow.toByte()
        )
    }

    fun tsToString(rawSec: Int): String = try {
        val ts = (rawSec.toLong() + SEC_2001) * 1000L
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
    } catch (_: Exception) {
        "raw:$rawSec"
    }

    // ── Pre-built command packets ─────────────────────────────────────────────

    val GET_SUPPORT_FUNCTION = buildPacket(0x0201, byteArrayOf(0x47, 0x46))
    val GET_CHIP_SCHEME      = buildPacket(0x021B)
    val GET_DEVICE_INFO      = buildPacket(0x0200, byteArrayOf(0x47, 0x43))
    val GET_DEVICE_NAME      = buildPacket(0x0203, byteArrayOf(0x47, 0x50))
    val STOP_REAL            = buildPacket(0x0309, byteArrayOf(0x00, 0x00, 0x02, 0x90.toByte()))
    val CMD_0109             = buildPacket(0x0109, byteArrayOf(0x00, 0x00, 0x31, 0x37))
    // SettingHeartMonitor (0x010C) — enable background HR monitoring, 5-minute interval.
    // LivUp calls settingHeartMonitor(1, 5). Previous packet used 0x0104 (SettingUnit) by mistake.
    val SETTING_HEART_MONITOR = buildPacket(0x010C, byteArrayOf(0x01, 0x05))
    // SettingSpO2Monitor (0x0126) — enable background SpO2 monitoring, 5-minute interval.
    val SETTING_SPO2_MONITOR  = buildPacket(0x0126, byteArrayOf(0x01, 0x05))
    // NOTE: SettingHRVMonitor (0x0145) returns "unsupported key" on R01L — non-fatal.
    val SETTING_HRV_MONITOR   = buildPacket(0x0145, byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00))
    // AppStartMeasurement (0x032F) + AppControlWave (0x030B) — ECG emotional measurement.
    // LivUp sends both via appEmotionalMeasurementStart(). CONTROL_WAVE_START suppresses
    // normal sensor notifications while active; we send CONTROL_WAVE_STOP as soon as the
    // complete 0x0610 result arrives (hrv != 0) to restore SpO2 etc.
    val EMOTIONAL_START      = buildPacket(0x032F, byteArrayOf(0x01, 0x0C))
    val CONTROL_WAVE_START   = buildPacket(0x030B, byteArrayOf(0x01, 0x00))
    val CONTROL_WAVE_STOP    = buildPacket(0x030B, byteArrayOf(0x00, 0x00))
    val GET_NOW_STEP         = buildPacket(0x020C)
    val GET_POWER_STATS      = buildPacket(0x0225)
    // AppControlReal — realKey byte selects which stream to enable:
    //   0x00 = Sport/step  → 0x0600
    //   0x07 = Respiratory → 0x0607
    //   0x0A = Comprehensive (all metrics including glucose) → 0x060A
    val START_REAL           = buildPacket(0x0309, byteArrayOf(0x01, 0x00, 0x02, 0xA0.toByte()))
    val START_REAL_COMPREHENSIVE = buildPacket(0x0309, byteArrayOf(0x01, 0x0A, 0x02, 0xA0.toByte()))
    val START_REAL_RESP      = buildPacket(0x0309, byteArrayOf(0x01, 0x07, 0x02, 0xA0.toByte()))
    // One-shot snapshot of all current sensor values (HR, BP, SpO2, temp, resp, steps)
    val GET_ALL_REAL_DATA    = buildPacket(0x0220)
    // History pull — confirmed from sleep-sync.log (official LivUp app morning sync):
    //   Sport  0x0502 → data on 0x0511 → DeleteSport  0x0540
    //   Sleep  0x0504 → data on 0x0513 → DeleteSleep  0x0541
    //   Heart  0x0506 → data on 0x0515 → DeleteHeart  0x0542
    //   Blood  0x0508 → data on 0x0517 → DeleteBlood  0x0543
    // The old GET_ALL_HISTORY (0x0509) and GET_BODY_HISTORY (0x0533) are never used
    // by the official app and appear not to be supported by the ring firmware.
    val GET_SPORT_HISTORY    = buildPacket(0x0502)
    val GET_SLEEP_HISTORY    = buildPacket(0x0504)
    val GET_HEART_HISTORY    = buildPacket(0x0506)
    val GET_BLOOD_HISTORY    = buildPacket(0x0508)
    val DELETE_SPORT         = buildPacket(0x0540, byteArrayOf(0x02))
    val DELETE_SLEEP         = buildPacket(0x0541, byteArrayOf(0x02))
    val DELETE_HEART         = buildPacket(0x0542, byteArrayOf(0x02))
    val DELETE_BLOOD         = buildPacket(0x0543, byteArrayOf(0x02))
    val GET_REAL_SPO2        = buildPacket(0x0211, byteArrayOf(0x01))
    // HISTORY_ACK: must carry [0x00] payload (CRC-ok). Empty payload causes ring to retransmit.
    val HISTORY_ACK          = buildPacket(0x0580, byteArrayOf(0x00))

    fun keepalive(counter: Int) =
        buildPacket(0x032F, byteArrayOf(0x01, (counter and 0xFF).toByte()))

    fun settingTime() = buildPacket(0x0100, makeBleTime())

    // ── Sensor event model ────────────────────────────────────────────────────

    /**
     * A decoded real-time sensor event from C1 or C3.
     * Each subclass maps to one or more [db.SensorReading] rows.
     */
    sealed class SensorEvent {
        data class HeartRate(val bpm: Int) : SensorEvent()
        data class SpO2(val percent: Int) : SensorEvent()
        /** Valid only when [systolic] != 0. */
        data class BloodPressure(val systolic: Int, val diastolic: Int, val hr: Int) : SensorEvent()
        data class Steps(val count: Int, val distanceM: Int, val calories: Int) : SensorEvent()
        data class RespiratoryRate(val bpm: Int) : SensorEvent()
        data class HRV(val hrv_ms: Int, val stress: Int) : SensorEvent()
        data class Battery(val percent: Int, val version: String) : SensorEvent()
    }

    /**
     * Decode a packet arriving on C1 or C3.
     * Returns null for ack/control packets that produce no sensor data.
     * Returns a non-null [SensorEvent] for observable measurements.
     * Also returns the raw [datatype] and [payload] for control handling in the service.
     */
    data class Packet(
        val datatype: Int,
        val payload: ByteArray,
        val sensor: SensorEvent? = null,
    )

    fun parsePacket(data: ByteArray): Packet? {
        if (data.size < 6) return null
        val datatype = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val payload  = data.copyOfRange(4, data.size - 2)
        val sensor   = decodeSensor(datatype, payload)
        return Packet(datatype, payload, sensor)
    }

    private fun decodeSensor(datatype: Int, p: ByteArray): SensorEvent? = when (datatype) {

        0x0601 -> if (p.isNotEmpty()) SensorEvent.HeartRate(p[0].u8()) else null

        0x0602 -> if (p.isNotEmpty()) SensorEvent.SpO2(p[0].u8()) else null

        0x0603 -> if (p.size >= 3 && p[0] != 0.toByte()) {
            SensorEvent.BloodPressure(p[0].u8(), p[1].u8(), p[2].u8())
        } else null  // zeros = still measuring

        0x0600 -> if (p.size >= 6) SensorEvent.Steps(
            count     = p[0].u8() or (p[1].u8() shl 8),
            distanceM = p[2].u8() or (p[3].u8() shl 8),
            calories  = p[4].u8() or (p[5].u8() shl 8),
        ) else null

        0x0607 -> if (p.isNotEmpty()) SensorEvent.RespiratoryRate(p[0].u8()) else null

        // 0x0610 = Real_UploadBodyData — HRV/stress/body metrics stream
        // Full layout (DataUnpack.unpackBodyData):
        //   [0] loadIndexInt  [1] loadIndexFloat
        //   [2] hrvInt        [3] hrvFloat
        //   [4] pressureInt   [5] pressureFloat  ← sentinel: 15 = still measuring
        //   [6] bodyInt       [7] bodyFloat
        //   [8] sympatheticInt [9] sympatheticFloat
        //   [10..11] SDNN (2-byte LE)
        //   [12] VO2max  [13] pnn50
        //   [14..15] RMSSD  [16..17] LF  [18..19] HF  [20] LF/HF (/10)
        // Only sentinel is pressureFloat==15. stress==0 is a valid reading.
        0x0610 -> if (p.size >= 6 && p[5].u8() != 15)
            SensorEvent.HRV(hrv_ms = p[2].u8(), stress = p[4].u8() * 10 + p[5].u8())
        else null

        // 0x060A = Real_UploadComprehensive
        // Byte 7: HR, 8: SBP, 9: DBP, 10: SpO2, 11: resp rate
        // Discard all-zero packets (ring warming up)
        0x060A -> null  // handled field-by-field in RingService.handleComprehensive()

        0x0200 -> if (p.size >= 6) {
            SensorEvent.Battery(
                percent = p[5].u8(),
                version = "${p[3].u8()}.${p[2].u8()}",
            )
        } else null

        else -> null
    }

    // ── History decoding ──────────────────────────────────────────────────────

    data class HistoryRecord(
        val type: String,
        val timestampMs: Long,
        val values: Map<String, Double>,
    )

    /**
     * Decode the BodyData history stream (0x0534), 28 bytes per record.
     * Layout verified against DataUnpack.java case 51 (HistoryBodyData):
     *   [0..3]   timestamp (LE, seconds since 2001-01-01)
     *   [4]      loadIndexInteger
     *   [5]      loadIndexFloat
     *   [6]      hrvInteger
     *   [7]      hrvFloat
     *   [8]      pressureInteger  (stress tens digit)
     *   [9]      pressureFloat    (stress units digit)  → stress = b[8]*10 + b[9]
     *   [10]     bodyInteger
     *   [11]     bodyFloat
     *   [12]     sympatheticInteger
     *   [13]     sympatheticFloat
     *   [14..15] SDNN (2-byte LE)
     *   [16]     VO2max
     *   [17]     pnn50
     *   [18..19] RMSSD (2-byte LE)
     *   [20..21] LF   (2-byte LE)
     *   [22..23] HF   (2-byte LE)
     *   [24]     LF/HF × 10
     *   [25..27] unused
     */
    fun decodeBodyHistory(raw: ByteArray): List<HistoryRecord> {
        val records = mutableListOf<HistoryRecord>()
        var offset = 0
        while (offset + 28 <= raw.size) {
            val d = raw
            val tsRing = ByteBuffer.wrap(d, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            val ts = (tsRing + SEC_2001) * 1000L
            val stress  = d[offset + 8].u8() * 10 + d[offset + 9].u8()
            val hrv     = d[offset + 6].u8()
            val hrvFrac = d[offset + 7].u8()
            val sdnn    = d[offset + 14].u8() or (d[offset + 15].u8() shl 8)
            val vo2     = d[offset + 16].u8()
            val rmssd   = d[offset + 18].u8() or (d[offset + 19].u8() shl 8)
            records += HistoryRecord(
                type        = "body",
                timestampMs = ts,
                values      = buildMap {
                    put("stress", stress.toDouble())
                    if (hrv > 0 || hrvFrac > 0) put("hrv", hrv + hrvFrac / 10.0)
                    if (sdnn > 0) put("sdnn", sdnn.toDouble())
                    if (vo2 > 0)  put("vo2max", vo2.toDouble())
                    if (rmssd > 0) put("rmssd", rmssd.toDouble())
                },
            )
            offset += 28
        }
        return records
    }

    /**
     * Decode the AllHistory stream (0x0518), 20 bytes per record.
     * Verified against DataUnpack.java case 9 (Health_HistoryAll).
     */
    fun decodeAllHistory(raw: ByteArray): List<HistoryRecord> {
        val records = mutableListOf<HistoryRecord>()
        var offset = 0
        while (offset + 20 <= raw.size) {
            val d = raw
            val tsRing = ByteBuffer.wrap(d, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            val ts = (tsRing + SEC_2001) * 1000L
            val hr   = d[offset + 6].u8()
            val sbp  = d[offset + 7].u8()
            val dbp  = d[offset + 8].u8()
            val spo2 = d[offset + 9].u8()
            val resp = d[offset + 10].u8()
            val hrv  = d[offset + 11].u8()
            val gluc = d[offset + 17].u8()
            val steps = d[offset + 4].u8() or (d[offset + 5].u8() shl 8)
            val tempInt = d[offset + 13].u8()
            val tempFrac = d[offset + 14].u8()

            records += HistoryRecord(
                type        = "health",
                timestampMs = ts,
                values      = buildMap {
                    if (hr   > 0) put("hr",   hr.toDouble())
                    if (sbp  > 0) put("systolic", sbp.toDouble())
                    if (dbp  > 0) put("diastolic", dbp.toDouble())
                    if (spo2 > 0) put("spo2", spo2.toDouble())
                    if (resp > 0) put("resp_rate", resp.toDouble())
                    if (hrv  > 0) put("hrv", hrv.toDouble())
                    if (steps > 0) put("steps", steps.toDouble())
                    if (gluc > 0) put("blood_glucose", gluc / 10.0)
                    if (tempInt > 0 && tempFrac != 0xFF) {
                        put("temperature", tempInt + tempFrac / 10.0)
                    }
                },
            )
            offset += 20
        }
        return records
    }

    /**
     * Decode the Sport history stream (0x0511, requested via 0x0502).
     * Confirmed from DataUnpack.java case 2 (Health_HistorySport) — 14 bytes per record:
     *   [0..3]   sportStartTime uint32 LE — seconds since 2001-01-01
     *   [4..7]   sportEndTime   uint32 LE — seconds since 2001-01-01
     *   [8..9]   steps          uint16 LE
     *   [10..11] distance       uint16 LE (metres)
     *   [12..13] calories       uint16 LE
     */
    fun decodeSportHistory(raw: ByteArray): List<HistoryRecord> {
        val records = mutableListOf<HistoryRecord>()
        var offset = 0
        while (offset + 14 <= raw.size) {
            val startSec = ByteBuffer.wrap(raw, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val steps    = raw[offset + 8].u8() or (raw[offset + 9].u8() shl 8)
            val distance = raw[offset + 10].u8() or (raw[offset + 11].u8() shl 8)
            val calories = raw[offset + 12].u8() or (raw[offset + 13].u8() shl 8)
            if (startSec > 0L) {
                records += HistoryRecord(
                    type        = "sport",
                    timestampMs = (startSec + SEC_2001) * 1000L,
                    values      = buildMap {
                        if (steps    > 0) put("steps",      steps.toDouble())
                        if (distance > 0) put("distance_m", distance.toDouble())
                        if (calories > 0) put("calories",   calories.toDouble())
                    },
                )
            }
            offset += 14
        }
        return records
    }

    /**
     * Decode the Heart Rate history stream (0x0515, requested via 0x0506).
     * Confirmed from DataUnpack.java case 6 (Health_HistoryHeart) — 6 bytes per record:
     *   [0..3] timestamp uint32 LE — seconds since 2001-01-01
     *   [4]    mode      uint8  (measurement mode; not persisted)
     *   [5]    hr        uint8  (bpm)
     */
    fun decodeHeartHistory(raw: ByteArray): List<HistoryRecord> {
        val records = mutableListOf<HistoryRecord>()
        var offset = 0
        while (offset + 6 <= raw.size) {
            val tsSec = ByteBuffer.wrap(raw, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val hr = raw[offset + 5].u8()
            if (tsSec > 0L && hr > 0) {
                records += HistoryRecord(
                    type        = "hr",
                    timestampMs = (tsSec + SEC_2001) * 1000L,
                    values      = mapOf("hr" to hr.toDouble()),
                )
            }
            offset += 6
        }
        return records
    }

    /**
     * Decode the Blood Pressure history stream (0x0517, requested via 0x0508).
     * Confirmed from DataUnpack.java case 8 (Health_HistoryBlood) — 8 bytes per record:
     *   [0..3] timestamp  uint32 LE — seconds since 2001-01-01
     *   [4]    isInflated uint8  (1 = cuff-style inflated measurement)
     *   [5]    SBP        uint8  (mmHg systolic)
     *   [6]    DBP        uint8  (mmHg diastolic)
     *   [7]    unused
     */
    fun decodeBloodHistory(raw: ByteArray): List<HistoryRecord> {
        val records = mutableListOf<HistoryRecord>()
        var offset = 0
        while (offset + 8 <= raw.size) {
            val tsSec = ByteBuffer.wrap(raw, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val sbp = raw[offset + 5].u8()
            val dbp = raw[offset + 6].u8()
            if (tsSec > 0L && sbp > 0) {
                records += HistoryRecord(
                    type        = "blood",
                    timestampMs = (tsSec + SEC_2001) * 1000L,
                    values      = buildMap {
                        put("systolic",  sbp.toDouble())
                        put("diastolic", dbp.toDouble())
                    },
                )
            }
            offset += 8
        }
        return records
    }

    /**
     * Decode the Sleep history stream (datatype 0x0513, requested via 0x0504).
     *
     * Format confirmed 100% from live logcat of the official LivUp app (sleep-sync.log)
     * and from DataUnpack.java / YcProductPluginHealthData.java in the decompiled SDK.
     * The old decoder (12-byte flat records from best-script.py's 0x0505 guess) was wrong
     * and never matched real ring data.
     *
     * The reassembled payload is a sequence of one or more sleep sessions, each structured as:
     *
     *   Session header — 20 bytes:
     *     [0]      always 0x01 (marker)
     *     [1]      always 0x01
     *     [2..3]   totalLen uint16 LE — TOTAL byte length of this session INCLUDING the
     *              20-byte header. Stage byte count = totalLen - 20.
     *              Confirmed: DataUnpack.java line 1751 uses (i20 - 20) as stage count,
     *              and live 0x0513 packet = 108 bytes = 20 header + 88 stage data (11 × 8).
     *     [4..7]   sleepStartTime uint32 LE — seconds since 2001-01-01
     *     [8..11]  sleepEndTime   uint32 LE — seconds since 2001-01-01
     *     [12..13] deepSleepCount uint16 LE — 0xFFFF signals "new sleep protocol"
     *     [14..15] lightSleepLen  uint16 LE (only valid in old protocol)
     *     [16..17] deepSleepLen   uint16 LE (only valid in old protocol)
     *     [18..19] remSleepLen    uint16 LE (only valid in old protocol, may be 0)
     *
     *   Stage records — 8 bytes each, totalLen / 8 records:
     *     [0]    stageType: 0xF1=deep, 0xF2=light, 0xF3=REM, 0xF4=awake, 0xF5=nap
     *     [1..4] stageTimestamp uint32 LE — seconds since 2001-01-01
     *     [5..7] stageDuration  uint24 LE — duration in seconds
     *
     * Multiple sessions may be concatenated; advance by totalLen per session (header included).
     */
    /** A decoded sleep session bundled with its individual stage records for DB storage. */
    data class SleepSessionDecoded(
        val session: SleepSession,
        val stages:  List<dev.ringbridge.db.SleepStage>,
    )

    fun decodeSleepHistory(raw: ByteArray): List<SleepSessionDecoded> {
        val results = mutableListOf<SleepSessionDecoded>()
        var pos = 0

        while (pos + 20 <= raw.size) {
            // ── Session header ────────────────────────────────────────────────
            val totalLen = ByteBuffer.wrap(raw, pos + 2, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

            // totalLen INCLUDES the 20-byte header. Stage bytes = totalLen - 20.
            // DataUnpack.java line 1751: `(i30 - i19) + 8 <= i20 - 20` where i20=totalLen.
            // Live packet: totalLen=108 = 20-byte header + 88-byte stage data (11 × 8 bytes).
            val stageByteCount = totalLen - 20

            val startSec = ByteBuffer.wrap(raw, pos + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val endSec   = ByteBuffer.wrap(raw, pos + 8, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

            val startMs = (startSec + SEC_2001) * 1000L
            val endMs   = (endSec   + SEC_2001) * 1000L

            val stageStart = pos + 20
            val stageEnd   = stageStart + stageByteCount  // = pos + totalLen ✓

            // Guard: make sure the full stage block fits in the buffer
            if (stageByteCount < 0 || stageEnd > raw.size || stageByteCount % 8 != 0) {
                break   // malformed; stop rather than crash
            }

            // ── Stage records ─────────────────────────────────────────────────
            var deepSec  = 0L
            var lightSec = 0L
            var remSec   = 0L
            var wakeSec  = 0L
            var napSec   = 0L

            val stageList = mutableListOf<dev.ringbridge.db.SleepStage>()

            var i = stageStart
            while (i + 8 <= stageEnd) {
                val stageType = raw[i].u8()
                val stageTsSec = ByteBuffer.wrap(raw, i + 1, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val stageTs  = (stageTsSec + SEC_2001) * 1000L
                val durSec   = (raw[i + 5].u8()
                    or (raw[i + 6].u8() shl 8)
                    or (raw[i + 7].u8() shl 16)).toLong()

                when (stageType) {
                    0xF1 -> deepSec  += durSec
                    0xF2 -> lightSec += durSec
                    0xF3 -> remSec   += durSec
                    0xF4 -> wakeSec  += durSec
                    0xF5 -> napSec   += durSec
                }

                if (startMs > 0L && stageTs > 0L) {
                    stageList += dev.ringbridge.db.SleepStage(
                        sessionStartMs = startMs,
                        timestamp      = stageTs,
                        stageType      = stageType,
                        durationSec    = durSec,
                    )
                }
                i += 8
            }

            val totalSleepMs = (deepSec + lightSec + remSec + napSec) * 1000L

            if (startMs > 0L && endMs > startMs) {
                results += SleepSessionDecoded(
                    session = SleepSession(
                        startMs      = startMs,
                        endMs        = endMs,
                        totalSleepMs = totalSleepMs,
                        deepSleepMs  = deepSec  * 1000L,
                        lightSleepMs = lightSec * 1000L,
                        remSleepMs   = remSec   * 1000L,
                        napMs        = napSec   * 1000L,
                        wakeMs       = wakeSec  * 1000L,
                    ),
                    stages = stageList,
                )
            }

            pos = stageEnd   // advance past header + stage records
        }

        return results
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Unsigned byte value as Int. */
    private fun Byte.u8() = toInt() and 0xFF
}
