package io.github.sc00tz.ringbridge

import io.github.sc00tz.ringbridge.ring.Codec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The same vectors core/codec.py is tested against. */
class CodecTest {
    private val vectors = JSONObject(javaClass.classLoader!!.getResource("vectors.json")!!.readText())
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun crcCheckValue() = assertEquals(0x29B1, Codec.crc16("123456789".toByteArray()))

    @Test fun frames() {
        val frames = vectors.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val v = frames.getJSONObject(i)
            val raw = Codec.frame(v.getInt("code"), hex(v.getString("payload")))
            assertEquals(v.getString("hex"), raw.hex())
            val parsed = Codec.parse(raw)
            assertEquals(v.getInt("code"), parsed.code)
            assertArrayEquals(hex(v.getString("payload")), parsed.payload)
        }
    }

    @Test fun badCrcDetected() = assertFalse(Codec.parse(hex("0200080047436fed")).crcOk)

    @Test fun assemblerSplitsAndJoins() {
        val a = Codec.Assembler()
        val f1 = Codec.frame(0x0200, hex("4743"))
        val f2 = Codec.frame(0x021B)
        assertEquals(0, a.feed(f1.copyOfRange(0, 3)).size)
        val out = a.feed(f1.copyOfRange(3, f1.size) + f2)
        assertEquals(listOf(f1.hex(), f2.hex()), out.map { it.hex() })
    }

    @Test fun historyRecords() {
        val history = vectors.getJSONArray("history")
        for (i in 0 until history.length()) {
            val v = history.getJSONObject(i)
            val got = Codec.decodeHistory(v.getString("name"), hex(v.getString("hex")))
            val samples = v.getJSONArray("samples")
            assertEquals(v.getString("name"), samples.length(), got.samples.size)
            for (j in 0 until samples.length()) {
                val s = samples.getJSONArray(j)
                assertEquals(s.getString(0), got.samples[j].metric)
                assertEquals(s.getString(1), got.samples[j].local)
                assertEquals(s.getDouble(2), got.samples[j].value, 1e-9)
            }
            val activity = v.getJSONArray("activity")
            assertEquals(activity.length(), got.activity.size)
            for (j in 0 until activity.length()) {
                val a = activity.getJSONObject(j)
                assertEquals(a.getString("start"), got.activity[j].start)
                assertEquals(a.getInt("steps"), got.activity[j].steps)
                assertEquals(a.getInt("distance_m"), got.activity[j].distanceM)
            }
            val sleep = v.getJSONArray("sleep")
            assertEquals(sleep.length(), got.sleep.size)
            for (j in 0 until sleep.length()) {
                val s = sleep.getJSONObject(j)
                assertEquals(s.getString("start"), got.sleep[j].start)
                val segs = s.getJSONArray("segments")
                assertEquals(segs.length(), got.sleep[j].segments.size)
                for (k in 0 until segs.length()) {
                    assertEquals(segs.getJSONObject(k).getString("stage"), got.sleep[j].segments[k].stage)
                    assertEquals(segs.getJSONObject(k).getInt("seconds"), got.sleep[j].segments[k].seconds)
                }
            }
            if (v.has("workouts")) {
                val w: JSONArray = v.getJSONArray("workouts")
                assertEquals(w.length(), got.workouts.size)
                for (j in 0 until w.length()) {
                    assertEquals(w.getJSONObject(j).getInt("ring_code"), got.workouts[j].ringCode)
                    assertEquals(w.getJSONObject(j).getLong("steps"), got.workouts[j].steps)
                }
            }
        }
    }
}
