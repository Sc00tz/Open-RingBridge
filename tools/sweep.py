"""Try each measurement selector on 0x032F and record what the ring sends."""
import asyncio
import json
import sys
import time

from yc import LOG_DIR, Session, parse

LIMIT = int(__import__("os").environ.get("LIMIT", "45"))


async def run_one(s, sel):
    await s.send(0x032F, bytes([1, sel]))
    t0 = time.time()
    seen, ack, result = {}, None, None
    while time.time() - t0 < LIMIT:
        try:
            raw = await s.recv(LIMIT - (time.time() - t0))
        except asyncio.TimeoutError:
            break
        code, body, _ = parse(raw)
        if code == 0x032F and ack is None:
            ack = body.hex()
            if body[:1] != b"\x00":
                break
        elif code == 0x040E:
            result = body.hex(" ")
            print(f"     result after {time.time() - t0:.0f}s: {result}")
            break
        else:
            seen.setdefault(f"{code:#06x}", []).append(body.hex(" "))
    await s.send(0x032F, bytes([0, sel]))
    await asyncio.sleep(2)
    while not s.frames.empty():
        code, body, _ = parse(s.frames.get_nowait())
        if code != 0x032F:
            seen.setdefault(f"{code:#06x}", []).append(body.hex(" "))
    return {"selector": sel, "ack": ack, "result": result, "frames": seen, "seconds": round(time.time() - t0)}


async def main():
    selectors = [int(x) for x in sys.argv[1:]] or list(range(9))
    async with Session(label="sweep") as s:
        print("device info", (await s.ask(0x0200, b"GC", timeout=4)).hex(" "))
        print("measurement functions", (await s.ask(0x0228, b"GF", timeout=4) or b"").hex(" "))
        for t in range(1, 6):
            await s.send(0x0309, bytes([1, t, 2]))
            await asyncio.sleep(0.4)
        while not s.frames.empty():
            code, body, _ = parse(s.frames.get_nowait())
            print(f"  live-on {code:#06x} {body.hex(' ')}")
        out = []
        for sel in selectors:
            print(f"== selector {sel}", flush=True)
            r = await run_one(s, sel)
            out.append(r)
            summary = {k: (len(v), v[0], v[-1]) for k, v in r["frames"].items()}
            print(f"     ack={r['ack']} result={r['result']} {r['seconds']}s frames={summary}", flush=True)
        for t in range(1, 6):
            await s.send(0x0309, bytes([0, t, 2]))
            await asyncio.sleep(0.3)
        (LOG_DIR / f"sweep-{int(time.time())}.json").write_text(json.dumps(out, indent=1))
        print("log:", s.log_path)


asyncio.run(main())
