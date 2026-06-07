package dev.ringbridge

import dev.ringbridge.RingProtocol.SensorEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [RingProtocol] — the byte-level packet framing and all
 * sensor / history decoders. Pure JVM (no Android deps), runs under `./gradlew test`.
 *
 * Test vectors are built directly from the documented YCBT byte layouts in
 * Ring_Protocol_Documentation.md, so a failure here means a decoder drifted from
 * the protocol, not that the test needs updating.
 */
class RingProtocolTest {

    /** Seconds between Unix epoch and the ring epoch (2001-01-01) — mirrors RingProtocol. */
    private val SEC_2001 = 946684800L

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )
    private fun le24(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
    )
    private fun expectedMs(tsRing: Long) = (tsRing + SEC_2001) * 1000L

    // ── CRC & framing ───────────────────────────────────────────────────────

    @Test
    fun crc16_isStableForKnownInput() {
        // Empty-payload packet header for datatype 0x0225 (GetPowerStats), length 6.
        val header = byteArrayOf(0x02, 0x25, 0x06, 0x00)
        val crc = RingProtocol.crc16(header, 4)
        // CRC must be deterministic and fit in 16 bits.
        assertEquals(crc, RingProtocol.crc16(header, 4))
        assertTrue(crc in 0..0xFFFF)
    }

    @Test
    fun buildPacket_framesHeaderLengthAndCrcCorrectly() {
        val payload = byteArrayOf(0x47, 0x46)
        val pkt = RingProtocol.buildPacket(0x0201, payload)

        // [type_hi][type_lo][len_lo][len_hi][payload...][crc_lo][crc_hi]
        assertEquals(payload.size + 6, pkt.size)
        assertEquals(0x02, pkt[0].toInt() and 0xFF)        // type hi
        assertEquals(0x01, pkt[1].toInt() and 0xFF)        // type lo
        assertEquals(pkt.size, pkt[2].toInt() and 0xFF)    // len lo (total len)
        assertEquals(0x00, pkt[3].toInt() and 0xFF)        // len hi
        assertArrayEquals(payload, pkt.copyOfRange(4, 4 + payload.size))

        // CRC trailer matches crc16 over everything but the trailer.
        val crc = RingProtocol.crc16(pkt, pkt.size - 2)
        assertEquals(crc and 0xFF, pkt[pkt.size - 2].toInt() and 0xFF)
        assertEquals((crc shr 8) and 0xFF, pkt[pkt.size - 1].toInt() and 0xFF)
    }

    @Test
    fun buildThenParse_roundTripsDatatypeAndPayload() {
        val payload = byteArrayOf(0x0A, 0x00, 0x02, 0xA0.toByte())
        val pkt = RingProtocol.buildPacket(0x0309, payload)
        val parsed = RingProtocol.parsePacket(pkt)!!
        assertEquals(0x0309, parsed.datatype)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun parsePacket_rejectsRunts() {
        assertNull(RingProtocol.parsePacket(byteArrayOf(0x06, 0x01, 0x06)))  // < 6 bytes
    }

    // ── Real-time sensor decoders ─────────────────────────────────────────────

    private fun sensorFor(datatype: Int, payload: ByteArray): SensorEvent? =
        RingProtocol.parsePacket(RingProtocol.buildPacket(datatype, payload))!!.sensor

    @Test
    fun decode_heartRate_0x0601() {
        val e = sensorFor(0x0601, byteArrayOf(72)) as SensorEvent.HeartRate
        assertEquals(72, e.bpm)
    }

    @Test
    fun decode_spo2_0x0602() {
        val e = sensorFor(0x0602, byteArrayOf(98.toByte())) as SensorEvent.SpO2
        assertEquals(98, e.percent)
    }

    @Test
    fun decode_bloodPressure_0x0603_validAndStillMeasuring() {
        val valid = sensorFor(0x0603, byteArrayOf(120.toByte(), 80.toByte(), 65)) as SensorEvent.BloodPressure
        assertEquals(120, valid.systolic)
        assertEquals(80, valid.diastolic)
        assertEquals(65, valid.hr)
        // systolic == 0 means "still measuring" → no event
        assertNull(sensorFor(0x0603, byteArrayOf(0, 0, 0)))
    }

    @Test
    fun decode_steps_0x0600_littleEndianFields() {
        // count=300 (0x012C), distance=1500 (0x05DC), calories=42 (0x2A)
        val p = le16(300) + le16(1500) + le16(42)
        val e = sensorFor(0x0600, p) as SensorEvent.Steps
        assertEquals(300, e.count)
        assertEquals(1500, e.distanceM)
        assertEquals(42, e.calories)
    }

    @Test
    fun decode_respiratoryRate_0x0607() {
        val e = sensorFor(0x0607, byteArrayOf(16)) as SensorEvent.RespiratoryRate
        assertEquals(16, e.bpm)
    }

    @Test
    fun decode_hrv_0x0610_computesStressAndHonorsSentinel() {
        // layout: [2]=hrv, [4]=pressureInt, [5]=pressureFloat (sentinel 15 = still measuring)
        // stress = [4]*10 + [5]
        val p = byteArrayOf(0, 0, 55, 0, 4, 2)
        val e = sensorFor(0x0610, p) as SensorEvent.HRV
        assertEquals(55, e.hrv_ms)
        assertEquals(42, e.stress)
        // pressureFloat == 15 → still measuring → null
        assertNull(sensorFor(0x0610, byteArrayOf(0, 0, 55, 0, 4, 15)))
    }

    @Test
    fun decode_battery_0x0200_percentAndVersion() {
        // [2]=minor, [3]=major, [5]=percent → version "major.minor"
        val p = byteArrayOf(0, 0, 13, 1, 0, 88.toByte())
        val e = sensorFor(0x0200, p) as SensorEvent.Battery
        assertEquals(88, e.percent)
        assertEquals("1.13", e.version)
    }

    @Test
    fun decode_comprehensive_0x060A_isHandledFieldByField_notHere() {
        // 0x060A returns no SensorEvent — RingService.handleComprehensive() decodes it.
        assertNull(sensorFor(0x060A, ByteArray(21)))
    }

    // ── History decoders ──────────────────────────────────────────────────────

    @Test
    fun decodeHeartHistory_6BytesPerRecord() {
        val tsRing = 700_000_000L
        // [0..3] ts LE, [4] mode, [5] hr
        val raw = le32(tsRing) + byteArrayOf(0x01, 66)
        val recs = RingProtocol.decodeHeartHistory(raw)
        assertEquals(1, recs.size)
        assertEquals("hr", recs[0].type)
        assertEquals(expectedMs(tsRing), recs[0].timestampMs)
        assertEquals(66.0, recs[0].values["hr"]!!, 0.001)
    }

    @Test
    fun decodeHeartHistory_skipsZeroHrAndZeroTs() {
        val raw = le32(700_000_000L) + byteArrayOf(0x01, 0) +   // hr=0 → skip
                  le32(0L) + byteArrayOf(0x01, 70)              // ts=0 → skip
        assertTrue(RingProtocol.decodeHeartHistory(raw).isEmpty())
    }

    @Test
    fun decodeBloodHistory_8BytesPerRecord() {
        val tsRing = 700_000_000L
        // [0..3] ts, [4] isInflated, [5] SBP, [6] DBP, [7] unused
        val raw = le32(tsRing) + byteArrayOf(0x01, 118.toByte(), 79, 0x00)
        val recs = RingProtocol.decodeBloodHistory(raw)
        assertEquals(1, recs.size)
        assertEquals(118.0, recs[0].values["systolic"]!!, 0.001)
        assertEquals(79.0, recs[0].values["diastolic"]!!, 0.001)
    }

    @Test
    fun decodeSportHistory_14BytesPerRecord() {
        val startRing = 700_000_000L
        val endRing = startRing + 3600
        // [0..3] start, [4..7] end, [8..9] steps, [10..11] distance, [12..13] calories
        val raw = le32(startRing) + le32(endRing) + le16(8000) + le16(6200) + le16(310)
        val recs = RingProtocol.decodeSportHistory(raw)
        assertEquals(1, recs.size)
        assertEquals("sport", recs[0].type)
        assertEquals(expectedMs(startRing), recs[0].timestampMs)
        assertEquals(8000.0, recs[0].values["steps"]!!, 0.001)
        assertEquals(6200.0, recs[0].values["distance_m"]!!, 0.001)
        assertEquals(310.0, recs[0].values["calories"]!!, 0.001)
    }

    // ── Sleep history (header + stage records) ─────────────────────────────────

    @Test
    fun decodeSleepHistory_singleSessionWithStages() {
        val startRing = 700_000_000L
        val endRing = startRing + 8 * 3600          // 8h later
        val deepTs = startRing
        val deepDurSec = 3600L                       // 1h deep

        // Stage record: [0] type(0xF1 deep), [1..4] ts LE, [5..7] duration uint24 LE
        val stage = byteArrayOf(0xF1.toByte()) + le32(deepTs) + le24(deepDurSec)
        assertEquals(8, stage.size)

        // Session header (20 bytes): [0]=1 [1]=1 [2..3]=totalLen [4..7]=start [8..11]=end ...
        val totalLen = 20 + stage.size               // 28
        val header = ByteArray(20)
        header[0] = 0x01; header[1] = 0x01
        le16(totalLen).copyInto(header, 2)
        le32(startRing).copyInto(header, 4)
        le32(endRing).copyInto(header, 8)

        val decoded = RingProtocol.decodeSleepHistory(header + stage)
        assertEquals(1, decoded.size)
        val s = decoded[0].session
        assertEquals(expectedMs(startRing), s.startMs)
        assertEquals(expectedMs(endRing), s.endMs)
        assertEquals(deepDurSec * 1000L, s.deepSleepMs)
        assertEquals(deepDurSec * 1000L, s.totalSleepMs)  // only deep counted
        assertEquals(1, decoded[0].stages.size)
        assertEquals(0xF1, decoded[0].stages[0].stageType)
        assertEquals(deepDurSec, decoded[0].stages[0].durationSec)
    }

    @Test
    fun decodeSleepHistory_malformedStageBlockStopsGracefully() {
        // totalLen claims 27 stage-bytes (not a multiple of 8) → decoder must bail, not crash.
        val header = ByteArray(20)
        header[0] = 0x01; header[1] = 0x01
        le16(47).copyInto(header, 2)   // 47 - 20 = 27, not %8
        le32(700_000_000L).copyInto(header, 4)
        le32(700_003_600L).copyInto(header, 8)
        val decoded = RingProtocol.decodeSleepHistory(header + ByteArray(27))
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun makeBleTime_isEightBytes() {
        // year(2) + month + day + hour + min + sec + dow = 8 bytes
        assertEquals(8, RingProtocol.makeBleTime().size)
    }

    @Test
    fun historyAck_carriesZeroPayload() {
        // Critical: empty payload makes the ring retransmit the whole stream.
        val parsed = RingProtocol.parsePacket(RingProtocol.HISTORY_ACK)!!
        assertEquals(0x0580, parsed.datatype)
        assertEquals(1, parsed.payload.size)
        assertEquals(0x00, parsed.payload[0].toInt())
    }
}
