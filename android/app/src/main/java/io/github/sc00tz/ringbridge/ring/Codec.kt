package io.github.sc00tz.ringbridge.ring

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Ring protocol: framing and record decoding. A port of core/codec.py; tests/vectors.json checks both. See PROTOCOL.md. */
object Codec {
    private val EPOCH: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)
    private val EARLIEST: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0)
    private val LATEST: LocalDateTime = LocalDateTime.of(2100, 1, 1, 0, 0)
    val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val STAGES = mapOf(0xF1 to "deep", 0xF2 to "light", 0xF3 to "rem", 0xF4 to "awake", 0xF5 to "nap")

    /** History type to (request code, chunk key). */
    val HISTORY = linkedMapOf(
        "steps" to (0x0502 to 17),
        "sleep" to (0x0504 to 19),
        "heart" to (0x0506 to 21),
        "blood" to (0x0508 to 23),
        "combined" to (0x0509 to 24),
        "body" to (0x0533 to 52),
        "workouts" to (0x052D to 46),
    )

    /** Dedicated streams come before combined, so combined only fills gaps. */
    val HISTORY_ORDER = listOf("steps", "sleep", "heart", "blood", "combined", "body", "workouts")

    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = ((crc ushr 8) or (crc shl 8)) and 0xFFFF
            crc = crc xor (data[i].toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = (crc xor (crc shl 12)) and 0xFFFF
            crc = (crc xor ((crc and 0xFF) shl 5)) and 0xFFFF
        }
        return crc
    }

    fun frame(code: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val length = payload.size + 6
        val head = byteArrayOf((code shr 8).toByte(), code.toByte(), length.toByte(), (length shr 8).toByte()) + payload
        val crc = crc16(head)
        return head + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }

    data class Frame(val code: Int, val payload: ByteArray, val crcOk: Boolean)

    fun parse(raw: ByteArray): Frame {
        val code = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        val got = (raw[raw.size - 2].toInt() and 0xFF) or ((raw[raw.size - 1].toInt() and 0xFF) shl 8)
        return Frame(code, raw.copyOfRange(4, raw.size - 2), got == crc16(raw, raw.size - 2))
    }

    /** Rebuild frames from notifications, which may split or join them. */
    class Assembler {
        private var buffer = ByteArray(0)

        fun feed(data: ByteArray): List<ByteArray> {
            buffer += data
            val out = mutableListOf<ByteArray>()
            while (buffer.size >= 4) {
                val length = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
                if (length < 6) { buffer = ByteArray(0); break }
                if (buffer.size < length) break
                out += buffer.copyOfRange(0, length)
                buffer = buffer.copyOfRange(length, buffer.size)
            }
            return out
        }
    }

    /** Payload for 0x0100. Weekday: 0 = Monday. */
    fun timePayload(now: LocalDateTime): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(now.year.toShort()).put(now.monthValue.toByte()).put(now.dayOfMonth.toByte())
        .put(now.hour.toByte()).put(now.minute.toByte()).put(now.second.toByte())
        .put((now.dayOfWeek.value - 1).toByte()).array()

    data class DeviceInfo(val deviceId: Int, val firmware: String, val batteryState: Int, val battery: Int, val charging: Boolean)

    /** 0x0200 reply. Byte 4 == 2 appears to mean charging [inferred]. */
    fun parseDeviceInfo(body: ByteArray): DeviceInfo? {
        if (body.size < 6) return null
        val u = { i: Int -> body[i].toInt() and 0xFF }
        return DeviceInfo(u(0) or (u(1) shl 8), "${u(3)}.${u(2)}", u(4), u(5), u(4) == 2)
    }

    /** Ring timestamp to local wall-clock text, or null if it can't be real (blank padding). */
    fun local(seconds: Long): String? {
        val value = EPOCH.plusSeconds(seconds)
        return if (!value.isBefore(EARLIEST) && value.isBefore(LATEST)) value.format(LOCAL) else null
    }

    /** The SDK keeps integer and fraction digits separately: (4, 1) means 4.1, not 4.01. */
    private fun joined(whole: Int, fraction: Int) = "$whole.$fraction".toDouble()

    data class Sample(val metric: String, val local: String, val value: Double)
    data class Activity(val start: String, val end: String, val steps: Int, val distanceM: Int, val kcal: Int)
    data class Segment(val stage: String, val start: String, val seconds: Int)
    data class Session(val start: String, val end: String, val segments: List<Segment>)
    data class RingWorkout(
        val start: String, val end: String, val steps: Long, val distanceM: Int, val kcal: Int, val ringCode: Int,
        val startMethod: Int, val hrAvg: Int, val seconds: Long, val hrMin: Int, val hrMax: Int,
    )
    data class Decoded(
        val samples: List<Sample> = emptyList(),
        val activity: List<Activity> = emptyList(),
        val sleep: List<Session> = emptyList(),
        val workouts: List<RingWorkout> = emptyList(),
    )

    /** Decode one history stream. Zero means "not measured", so zeros are left out. */
    fun decodeHistory(name: String, d: ByteArray): Decoded {
        val buf = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val u8 = { i: Int -> d[i].toInt() and 0xFF }
        val u16 = { i: Int -> buf.getShort(i).toInt() and 0xFFFF }
        val u32 = { i: Int -> buf.getInt(i).toLong() and 0xFFFFFFFFL }
        val samples = mutableListOf<Sample>()
        val activity = mutableListOf<Activity>()
        val sleep = mutableListOf<Session>()
        val workouts = mutableListOf<RingWorkout>()
        fun add(metric: String, local: String, value: Double) { if (value != 0.0) samples += Sample(metric, local, value) }

        when (name) {
            "heart" -> for (i in 0 until d.size - 5 step 6) {
                val local = local(u32(i)) ?: continue
                add("heart_rate", local, u8(i + 5).toDouble())
            }
            "blood" -> for (i in 0 until d.size - 7 step 8) {
                val local = local(u32(i)) ?: continue
                add("bp_systolic", local, u8(i + 5).toDouble())
                add("bp_diastolic", local, u8(i + 6).toDouble())
            }
            // Bytes 4-5 are a running step counter, so activity comes from the steps stream instead.
            // Temperature (constant 0.15) and fat (always 0) aren't real on this ring.
            "combined" -> for (i in 0 until d.size - 19 step 20) {
                val local = local(u32(i)) ?: continue
                val r = i + 6
                listOf("heart_rate" to u8(r), "bp_systolic" to u8(r + 1), "bp_diastolic" to u8(r + 2), "spo2" to u8(r + 3),
                    "respiration" to u8(r + 4), "hrv" to u8(r + 5), "cvrr" to u8(r + 6))
                    .forEach { (metric, value) -> add(metric, local, value.toDouble()) }
                add("glucose", local, u8(r + 11) / 10.0)
            }
            // VO2 max, pNN50, RMSSD, LF and HF were zero on every record, so they aren't kept.
            "body" -> for (i in 0 until d.size - 27 step 28) {
                val local = local(u32(i)) ?: continue
                val r = i + 4
                add("ring_load", local, joined(u8(r), u8(r + 1)))
                add("ring_hrv_index", local, joined(u8(r + 2), u8(r + 3)))
                add("stress", local, joined(u8(r + 4), u8(r + 5)))
                add("ring_body", local, joined(u8(r + 6), u8(r + 7)))
                add("ring_sympathetic", local, joined(u8(r + 8), u8(r + 9)))
                add("sdnn", local, u16(i + 14).toDouble())
            }
            "steps" -> for (i in 0 until d.size - 13 step 14) {
                val start = local(u32(i)) ?: continue
                val end = local(u32(i + 4)) ?: continue
                activity += Activity(start, end, u16(i + 8), u16(i + 10), u16(i + 12))
            }
            "sleep" -> {
                var i = 0
                while (i + 20 <= d.size) {
                    val length = u16(i + 2)
                    if (length < 20) break
                    val start = u32(i + 4)
                    val end = u32(i + 8)
                    val segments = mutableListOf<Segment>()
                    var j = i + 20
                    while (j + 8 <= i + length && j + 8 <= d.size) {
                        val local = local(u32(j + 1))
                        val seconds = u8(j + 5) or (u8(j + 6) shl 8) or (u8(j + 7) shl 16)
                        if (local != null) segments += Segment(STAGES[u8(j)] ?: "unknown", local, seconds)
                        j += 8
                    }
                    val s = local(start)
                    val e = local(end)
                    if (s != null && e != null) sleep += Session(s, e, segments)
                    i += length
                }
            }
            // The SDK picks the layout by length: 42-byte records, else 26 (the same fields, minus GPS and type).
            "workouts" -> {
                val size = if (d.isNotEmpty() && d.size % 42 == 0) 42 else if (d.isNotEmpty() && d.size % 26 == 0) 26 else 0
                if (size > 0) for (i in 0..d.size - size step size) {
                    val start = local(u32(i)) ?: continue
                    val end = local(u32(i + 4)) ?: continue
                    workouts += RingWorkout(start, end, u32(i + 8), u16(i + 12), u16(i + 14), u8(i + 16), u8(i + 17),
                        u8(i + 18), u32(i + 19), u8(i + 23), u8(i + 24))
                }
            }
            else -> throw IllegalArgumentException(name)
        }
        return Decoded(samples, activity, sleep, workouts)
    }
}
