"""Read history from the ring WITHOUT acknowledging it (no 0x0580 is sent)."""
import asyncio
import datetime as dt
import json
import struct
import sys

from yc import LOG_DIR, Session, crc16, parse

TYPES = {
    "steps": (0x0502, 17), "sleep": (0x0504, 19), "heart": (0x0506, 21), "blood": (0x0508, 23),
    "combined": (0x0509, 24), "workout": (0x052D, 46), "lab": (0x052F, 48), "body": (0x0533, 52),
    "wear": (0x0566, 103),
}
EPOCH = dt.datetime(2000, 1, 1)
STAGES = {0xF1: "deep", 0xF2: "light", 0xF3: "rem", 0xF4: "awake", 0xF5: "nap"}


def when(seconds):
    return (EPOCH + dt.timedelta(seconds=seconds)).isoformat(" ")


def decode(name, d):
    out = []
    if name == "heart":
        for i in range(0, len(d) - 5, 6):
            t, mode, bpm = struct.unpack_from("<IBB", d, i)
            out.append({"time": when(t), "mode": mode, "bpm": bpm})
    elif name == "steps":
        for i in range(0, len(d) - 13, 14):
            a, b, steps, dist, cal = struct.unpack_from("<IIHHH", d, i)
            out.append({"start": when(a), "end": when(b), "steps": steps, "distance": dist, "calories": cal})
    elif name == "combined":
        for i in range(0, len(d) - 19, 20):
            t, steps = struct.unpack_from("<IH", d, i)
            r = d[i + 6:i + 20]
            out.append({"time": when(t), "steps": steps, "hr": r[0], "sbp": r[1], "dbp": r[2], "spo2": r[3],
                        "resp": r[4], "hrv": r[5], "cvrr": r[6], "temp": r[7] + r[8] / 100, "fat": r[9] + r[10] / 100,
                        "sugar": r[11]})
    elif name == "sleep":
        i = 0
        while i + 20 <= len(d):
            length = struct.unpack_from("<H", d, i + 2)[0]
            start, end = struct.unpack_from("<II", d, i + 4)
            deep_count, a, b, c = struct.unpack_from("<HHHH", d, i + 12)
            segs, j = [], i + 20
            while j - (i + 20) + 8 <= length - 20:
                kind = d[j]
                seg_start = struct.unpack_from("<I", d, j + 1)[0]
                seg_len = d[j + 5] | (d[j + 6] << 8) | (d[j + 7] << 16)
                segs.append({"stage": STAGES.get(kind, hex(kind)), "start": when(seg_start), "seconds": seg_len})
                j += 8
            scale = 1 if deep_count == 0xFFFF else 60  # older format stores minutes
            out.append({"start": when(start), "end": when(end), "rem_seconds": a * scale,
                        "deep_seconds": b * scale, "light_seconds": c * scale, "segments": segs})
            if length < 20:
                break
            i += length
    elif name == "blood":
        for i in range(0, len(d) - 7, 8):
            t, mode, sbp, dbp = struct.unpack_from("<IBBB", d, i)
            out.append({"time": when(t), "mode": mode, "sbp": sbp, "dbp": dbp})
    elif name == "body":
        for i in range(0, len(d) - 27, 28):
            t = struct.unpack_from("<I", d, i)[0]
            r = d[i + 4:i + 14]
            sdnn = struct.unpack_from("<H", d, i + 14)[0]
            rmssd, lf, hf = struct.unpack_from("<HHH", d, i + 18)
            joined = lambda a, b: float(f"{a}.{b}")  # the SDK keeps these as integer and fraction digits
            out.append({"time": when(t), "load": joined(r[0], r[1]), "hrv_index": joined(r[2], r[3]),
                        "stress": joined(r[4], r[5]), "body": joined(r[6], r[7]), "sympathetic": joined(r[8], r[9]),
                        "sdnn": sdnn, "vo2max": d[i + 16], "pnn50": d[i + 17], "rmssd": rmssd, "lf": lf, "hf": hf,
                        "lf_hf": d[i + 24] / 10})
    elif name == "wear":
        for i in range(0, len(d) - 4, 5):
            t, status = struct.unpack_from("<IB", d, i)
            out.append({"time": when(t), "status": status})
    return out


async def read_one(s, name):
    code, chunk_key = TYPES[name]
    print(f"\n== {name} {code:#06x}")
    await s.send(code)
    header, chunks, end = None, [], None
    while True:
        try:
            raw = await s.recv(10.0)
        except asyncio.TimeoutError:
            print("   timed out waiting for more")
            break
        got, body, status = parse(raw)
        if got == code:
            header = body
            if len(body) <= 9:
                print(f"   header {body.hex(' ')}: no data")
                return None
            count, packets, total = struct.unpack_from("<HII", body)
            print(f"   header: {count} records, {packets} packets, {total} bytes")
        elif got == (code & 0xFF00) | chunk_key:
            chunks.append(body)
        elif got == 0x0580:
            end = body
            break
        else:
            print(f"   other {got:#06x} {body.hex(' ')}")
    data = b"".join(chunks)
    if end is not None:
        _, size, want = struct.unpack_from("<HHH", end)
        print(f"   end: size {size}, CRC {'ok' if crc16(data) == want else 'MISMATCH'}; got {len(data)} bytes. No ack sent.")
    (LOG_DIR / f"{name}.bin").write_bytes(data)
    rows = decode(name, data)
    (LOG_DIR / f"{name}.json").write_text(json.dumps(rows, indent=1))
    if rows:
        print(f"   decoded {len(rows)}; first {rows[0]}\n   last  {rows[-1]}")
    return rows


async def main(names):
    async with Session(label="history") as s:
        for name in names:
            await read_one(s, name)
            await asyncio.sleep(1.0)
        print("\nlog:", s.log_path)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:] or ["heart"]))
