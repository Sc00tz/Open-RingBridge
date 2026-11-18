"""Live test on a finger: standard HR, time sync, then custom stream with one heart measurement."""
import asyncio
import datetime as dt
import time
from yc import Session, parse

HR = "00002a37-0000-1000-8000-00805f9b34fb"
last = {"v": None}

def on_hr(_c, data):
    flags = data[0]
    bpm = int.from_bytes(data[1:3], "little") if flags & 1 else data[1]
    now = (flags, bpm)
    if now != last["v"]:
        print(f"   [{time.strftime('%X')}] 2A37 {bytes(data).hex(' ')} => {bpm} bpm")
        last["v"] = now

async def drain(s, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            raw = await s.recv(end - time.time())
        except asyncio.TimeoutError:
            return
        code, body, status = parse(raw)
        print(f"   [{time.strftime('%X')}] {code:#06x} {status} {body.hex(' ') or '(empty)'}")

def time_payload():
    n = dt.datetime.now()
    return bytes([n.year & 255, n.year >> 8, n.month, n.day, n.hour, n.minute, n.second, n.weekday()])

async def main():
    async with Session(label="live-worn") as s:
        print("== device info"); await s.ask(0x0200, b"GC", timeout=4)
        await s.client.start_notify(HR, on_hr)
        print("== standard HR alone, 25s"); await drain(s, 25)
        print("== set time 0x0100"); await s.ask(0x0100, time_payload(), timeout=4)
        print("== standard HR after time sync, 10s"); await drain(s, 10)
        print("== custom live on 0x0309 [1,1,2]"); await s.send(0x0309, bytes([1, 1, 2])); await drain(s, 3)
        print("== start measurement 0x032F [1,0], 50s"); await s.send(0x032F, bytes([1, 0])); await drain(s, 50)
        print("== stop measurement"); await s.send(0x032F, bytes([0, 0])); await drain(s, 3)
        print("== custom live off"); await s.send(0x0309, bytes([0, 1, 2])); await drain(s, 3)
        await s.client.stop_notify(HR)
        print("log:", s.log_path)

asyncio.run(main())
