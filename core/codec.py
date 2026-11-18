"""Ring protocol: framing and record decoding. Pure functions with no I/O.

Port this file to Kotlin first; tests/vectors.json checks both versions. See ../PROTOCOL.md.
"""
import datetime as dt
import struct

EPOCH = dt.datetime(2000, 1, 1)
EARLIEST = dt.datetime(2020, 1, 1)
LATEST = dt.datetime(2100, 1, 1)
STAGES = {0xF1: "deep", 0xF2: "light", 0xF3: "rem", 0xF4: "awake", 0xF5: "nap"}

# History type: (request code, chunk key).
HISTORY = {
    "steps": (0x0502, 17),
    "sleep": (0x0504, 19),
    "heart": (0x0506, 21),
    "blood": (0x0508, 23),
    "combined": (0x0509, 24),
    "body": (0x0533, 52),
    "workouts": (0x052D, 46),
}
# Dedicated streams come before combined, so combined only fills gaps.
HISTORY_ORDER = ["steps", "sleep", "heart", "blood", "combined", "body", "workouts"]


def crc16(data):
    crc = 0xFFFF
    for byte in data:
        crc = ((crc >> 8) | (crc << 8)) & 0xFFFF
        crc ^= byte
        crc ^= (crc & 0xFF) >> 4
        crc = (crc ^ (crc << 12)) & 0xFFFF
        crc = (crc ^ ((crc & 0xFF) << 5)) & 0xFFFF
    return crc


def frame(code, payload=b""):
    length = len(payload) + 6
    head = bytes([code >> 8, code & 0xFF, length & 0xFF, length >> 8]) + bytes(payload)
    crc = crc16(head)
    return head + bytes([crc & 0xFF, crc >> 8])


def parse(raw):
    """Return (code, payload, crc_ok) for one complete frame."""
    code = (raw[0] << 8) | raw[1]
    return code, bytes(raw[4:-2]), (raw[-2] | (raw[-1] << 8)) == crc16(raw[:-2])


class Assembler:
    """Rebuild frames from notifications, which may split or join them."""

    def __init__(self):
        self.buffer = bytearray()

    def feed(self, data):
        self.buffer += data
        out = []
        while len(self.buffer) >= 4:
            length = self.buffer[2] | (self.buffer[3] << 8)
            if length < 6:
                self.buffer.clear()
                break
            if len(self.buffer) < length:
                break
            out.append(bytes(self.buffer[:length]))
            del self.buffer[:length]
        return out


def time_payload(now):
    """Payload for 0x0100. Weekday: 0 = Monday."""
    return struct.pack("<HBBBBBB", now.year, now.month, now.day, now.hour, now.minute, now.second, now.weekday())


def parse_device_info(body):
    """0x0200 reply. Byte 4 == 2 appears to mean charging [inferred]."""
    if len(body) < 6:
        return None
    return {"device_id": body[0] | (body[1] << 8), "firmware": f"{body[3]}.{body[2]}",
            "battery_state": body[4], "battery": body[5], "charging": body[4] == 2}


def _local(seconds):
    """Ring timestamp to local wall-clock text, or None if it can't be real (blank padding)."""
    value = EPOCH + dt.timedelta(seconds=seconds)
    return value.isoformat(" ") if EARLIEST <= value < LATEST else None


def _joined(whole, fraction):
    """The SDK keeps integer and fraction digits separately: (4, 1) means 4.1, not 4.01."""
    return float(f"{whole}.{fraction}")


def decode_history(name, d):
    """Decode one history stream. Zero means "not measured", so zeros are left out."""
    samples, activity, sleep, workouts = [], [], [], []

    def add(metric, local, value):
        if value:
            samples.append((metric, local, value))

    if name == "heart":
        for i in range(0, len(d) - 5, 6):
            t, _, bpm = struct.unpack_from("<IBB", d, i)
            local = _local(t)
            if local:
                add("heart_rate", local, bpm)
    elif name == "blood":
        for i in range(0, len(d) - 7, 8):
            t, _, sbp, dbp = struct.unpack_from("<IBBB", d, i)
            local = _local(t)
            if local:
                add("bp_systolic", local, sbp)
                add("bp_diastolic", local, dbp)
    elif name == "combined":
        # Bytes 4-5 are a running step counter, so activity comes from the steps stream instead.
        # Temperature (constant 0.15) and fat (always 0) aren't real on this ring.
        for i in range(0, len(d) - 19, 20):
            local = _local(struct.unpack_from("<I", d, i)[0])
            if not local:
                continue
            r = d[i + 6:i + 20]
            for metric, value in (("heart_rate", r[0]), ("bp_systolic", r[1]), ("bp_diastolic", r[2]),
                                  ("spo2", r[3]), ("respiration", r[4]), ("hrv", r[5]), ("cvrr", r[6]),
                                  ("glucose", r[11] / 10)):
                add(metric, local, value)
    elif name == "body":
        # VO2 max, pNN50, RMSSD, LF and HF were zero on every record, so they aren't kept.
        for i in range(0, len(d) - 27, 28):
            local = _local(struct.unpack_from("<I", d, i)[0])
            if not local:
                continue
            r = d[i + 4:i + 14]
            add("ring_load", local, _joined(r[0], r[1]))
            add("ring_hrv_index", local, _joined(r[2], r[3]))
            add("stress", local, _joined(r[4], r[5]))
            add("ring_body", local, _joined(r[6], r[7]))
            add("ring_sympathetic", local, _joined(r[8], r[9]))
            add("sdnn", local, struct.unpack_from("<H", d, i + 14)[0])
    elif name == "steps":
        for i in range(0, len(d) - 13, 14):
            a, b, steps, distance, kcal = struct.unpack_from("<IIHHH", d, i)
            start, end = _local(a), _local(b)
            if start and end:
                activity.append({"start": start, "end": end, "steps": steps, "distance_m": distance, "kcal": kcal})
    elif name == "sleep":
        i = 0
        while i + 20 <= len(d):
            length = struct.unpack_from("<H", d, i + 2)[0]
            if length < 20:
                break
            start, end = struct.unpack_from("<II", d, i + 4)
            segments, j = [], i + 20
            while j + 8 <= i + length and j + 8 <= len(d):
                local = _local(struct.unpack_from("<I", d, j + 1)[0])
                seconds = d[j + 5] | (d[j + 6] << 8) | (d[j + 7] << 16)
                if local:
                    segments.append({"stage": STAGES.get(d[j], "unknown"), "start": local, "seconds": seconds})
                j += 8
            if _local(start) and _local(end):
                sleep.append({"start": _local(start), "end": _local(end), "segments": segments})
            i += length
    elif name == "workouts":
        # The SDK picks the layout by length: 42-byte records, else 26 (the same fields, minus GPS and type).
        size = 42 if len(d) % 42 == 0 else 26 if len(d) % 26 == 0 else 0
        for i in range(0, len(d) - size + 1, size) if size else ():
            a, b, steps, distance, kcal, mode, method, hr, seconds, hr_min, hr_max = struct.unpack_from("<IIIHHBBBIBB", d, i)
            start, end = _local(a), _local(b)
            if start and end:
                workouts.append({"start": start, "end": end, "steps": steps, "distance_m": distance, "kcal": kcal,
                                 "ring_code": mode, "start_method": method, "hr_avg": hr, "seconds": seconds,
                                 "hr_min": hr_min, "hr_max": hr_max})
    else:
        raise ValueError(name)
    return {"samples": samples, "activity": activity, "sleep": sleep, "workouts": workouts}
