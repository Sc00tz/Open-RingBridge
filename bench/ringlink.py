"""Bluetooth link to the ring. On Android this layer gets rewritten; codec.py's logic carries over.

Safety: this only reads history, sets the clock and runs measurements. It never sends the history
acknowledgement (0x0580), delete, reset or firmware commands.
"""
import asyncio
import datetime as dt
import os
import pathlib
import struct
import sys
import time

from bleak import BleakClient, BleakScanner

sys.path.insert(0, str(pathlib.Path(__file__).parent.parent / "core"))
import codec
import derived
import importer
import views

C1 = "be940001-7333-be46-b7ae-689e71722bd5"
C3 = "be940003-7333-be46-b7ae-689e71722bd5"
RING_NAME = os.environ.get("RING_NAME", "R01L C923")

# Selectors from the sweep in PROTOCOL.md. read(code, body) returns {metric: value} or None.
MEASURES = {
    "heart": {"selector": 0, "label": "Heart rate", "seconds": 20,
              "read": lambda c, b: {"heart_rate": b[0]} if c == 0x0601 and b else None},
    "blood": {"selector": 1, "label": "Blood pressure", "seconds": 20,
              "read": lambda c, b: {"bp_systolic": b[0], "bp_diastolic": b[1]} if c == 0x0603 and len(b) >= 2 else None},
    "spo2": {"selector": 2, "label": "Blood oxygen", "seconds": 65,
             "read": lambda c, b: {"spo2": b[0]} if c == 0x0602 and b else None},
    "glucose": {"selector": 5, "label": "Glucose", "seconds": 20,
                "read": lambda c, b: {"glucose": b[20] / 10} if c == 0x060A and len(b) > 20 else None},
    "hrv": {"selector": 10, "label": "HRV", "seconds": 20,
            "read": lambda c, b: {"hrv": b[3]} if c == 0x0603 and len(b) > 3 else None},
    "stress": {"selector": 12, "label": "Stress", "seconds": 12,
               "read": lambda c, b: {"stress": b[4]} if c == 0x0610 and len(b) > 4 else None},
}

WORKOUTS = views.WORKOUTS
HEART_STORE_SECONDS = 10


class RingError(Exception):
    pass


class Link:
    def __init__(self):
        self.client = None
        self.frames = asyncio.Queue()
        self.assembler = codec.Assembler()

    def _on_data(self, _characteristic, data):
        for raw in self.assembler.feed(data):
            self.frames.put_nowait(raw)

    @property
    def connected(self):
        return bool(self.client and self.client.is_connected)

    async def __aenter__(self):
        self.assembler = codec.Assembler()
        device = await BleakScanner.find_device_by_filter(
            lambda d, adv: (adv.local_name or d.name or "") == RING_NAME, timeout=20.0)
        if device is None:
            raise RingError(f"Couldn't find {RING_NAME}. It needs to be nearby, charged, and not connected to a phone.")
        self.client = BleakClient(device, timeout=25.0)
        await self.client.connect()
        await self.client.start_notify(C3, self._on_data)
        await self.client.start_notify(C1, self._on_data)
        return self

    async def __aexit__(self, *exc):
        if self.connected:
            await self.client.disconnect()

    async def set_live(self, enable):
        for t in range(1, 6):
            await self.send(0x0309, bytes([int(enable), t, 2]))
            await asyncio.sleep(0.1)

    async def send(self, code, payload=b""):
        await self.client.write_gatt_char(C1, codec.frame(code, payload), response=True)

    async def recv(self, timeout):
        return codec.parse(await asyncio.wait_for(self.frames.get(), timeout))

    async def ask(self, code, payload=b"", timeout=4.0):
        await self.send(code, payload)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                got, body, _ = await self.recv(deadline - time.monotonic())
            except asyncio.TimeoutError:
                return None
            if got == code:
                return body
        return None

    async def status(self):
        await self.ask(0x0100, codec.time_payload(dt.datetime.now()))
        body = await self.ask(0x0200, b"GC")
        return codec.parse_device_info(body) if body else None

    async def read_history(self, name):
        """Read one stream. Returns (bytes, verified). Never acknowledges."""
        code, chunk_key = codec.HISTORY[name]
        chunk_code = (code & 0xFF00) | chunk_key
        await self.send(code)
        chunks = []
        while True:
            try:
                got, body, _ = await self.recv(10.0)
            except asyncio.TimeoutError:
                raise RingError(f"The ring stopped responding while sending {name} history.")
            if got == code and len(body) <= 9:
                return b"", True
            if got == chunk_code:
                chunks.append(body)
            elif got == 0x0580:
                data = b"".join(chunks)
                want = struct.unpack_from("<HHH", body)[2]
                return data, codec.crc16(data) == want


async def sync(store, raw_dir, progress=lambda text: None):
    """Read every history stream, then import. Raw bytes are backed up even if a checksum fails."""
    folder = pathlib.Path(raw_dir) / dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    streams = {}
    progress("Connecting")
    async with Link() as link:
        status = await link.status()  # this sets the ring's clock
        set_ts = int(time.time())
        if status:
            store.set_device(status)
        for w in store.open_ring_workouts():  # a workout the connection dropped before it could be ended
            await link.ask(0x030C, bytes([0, w["ring_code"]]))
            store.mark_ring_closed(w["id"])
        for name in codec.HISTORY_ORDER:
            progress(f"Reading {name}")
            streams[name] = await link.read_history(name)
            await asyncio.sleep(0.5)
    progress("Importing")
    return importer.import_sync(store, set_ts, streams, folder)


async def measure(kind, store, on_value=lambda values: None, cancelled=lambda: False):
    """Run one measurement and store its last non-zero values with origin "live"."""
    spec = MEASURES[kind]
    selector = spec["selector"]
    limit = spec["seconds"] + 45
    values, finished = {}, False
    async with Link() as link:
        for t in range(1, 6):
            await link.send(0x0309, bytes([1, t, 2]))
            await asyncio.sleep(0.2)
        await link.send(0x032F, bytes([1, selector]))
        started = time.monotonic()
        accepted = False
        try:
            while time.monotonic() - started < limit and not cancelled():
                try:
                    code, body, _ = await link.recv(1.0)
                except asyncio.TimeoutError:
                    continue
                if code == 0x032F and not accepted:
                    if body[:1] != b"\x00":
                        raise RingError("The ring refused. It needs to be on your finger and off the charger.")
                    accepted = True
                elif code == 0x040E:
                    # [selector, 01] is success. Other statuses (02 seen) end it with no reading.
                    if len(body) >= 2 and body[1] != 1:
                        raise RingError(f"The ring stopped the measurement early (status {body[1]:02x}). "
                                        "Check the fit, keep still and try again.")
                    finished = True
                    break
                else:
                    got = spec["read"](code, body)
                    if got and all(got.values()):
                        values = got
                        on_value(values)
        finally:
            await link.send(0x032F, bytes([0, selector]))
            for t in range(1, 6):
                await link.send(0x0309, bytes([0, t, 2]))
                await asyncio.sleep(0.1)
    if cancelled():
        return {"values": {}, "cancelled": True}
    if not values:
        raise RingError("The ring finished without a reading. Check the fit and try again." if finished
                        else "No reading before the time limit. Check the fit and try again.")
    for metric, value in values.items():
        store.add_live(metric, value)
    return {"values": values, "finished": finished}


async def workout(kind, store, raw_dir, update=lambda **fields: None, stopped=lambda: False, link_class=Link):
    """Run one workout until stopped, keeping every raw frame so unknown fields can be decoded later.

    Heart readings are stored as they arrive. If the connection drops, it keeps trying to reconnect.
    """
    code = WORKOUTS[kind]["code"]
    first_sport, last_sport, heart, points, last_stored = None, None, [], [], 0.0
    link = link_class()
    await link.__aenter__()
    reply = await link.ask(0x030C, bytes([1, code]))
    if reply is None or reply[:1] != b"\x00":
        await link.__aexit__()
        raise RingError("The ring didn't start the workout. It needs to be on your finger and off the charger."
                        if reply is not None else "The ring didn't answer the workout command.")
    folder = pathlib.Path(raw_dir) / f"workout-{dt.datetime.now():%Y%m%d-%H%M%S}"
    folder.mkdir(parents=True, exist_ok=True)
    workout_id = store.start_workout(kind, code)
    update(message="Recording", workout_id=workout_id)
    ended_by_ring = False
    try:
        with open(folder / "frames.log", "w") as log:
            await link.set_live(True)
            while not stopped():
                if not link.connected:
                    update(message="Connection lost, reconnecting")
                    try:
                        await link.__aenter__()
                        await link.set_live(True)
                        update(message="Recording")
                    except Exception:
                        await asyncio.sleep(3)
                    continue
                try:
                    got, body, _ = await link.recv(1.0)
                except asyncio.TimeoutError:
                    continue
                log.write(f"{time.time():.1f} {got:04x} {body.hex()}\n")
                log.flush()
                if got == 0x0601 and body and body[0]:
                    heart.append(body[0])
                    update(heart=body[0])
                    if time.monotonic() - last_stored >= HEART_STORE_SECONDS:
                        last_stored = time.monotonic()
                        store.add_live("heart_rate", body[0], origin="workout")
                        points.append({"t": int(time.time()), "v": body[0]})
                        update(points=points)
                elif got == 0x0600 and len(body) >= 6:
                    last_sport = struct.unpack_from("<HHH", body)
                    first_sport = first_sport or last_sport
                    update(totals=derived.workout_totals(first_sport, last_sport, heart))
                elif got == 0x040C and body[:1] == b"\x00":
                    ended_by_ring = True
                    break
    finally:
        closed = ended_by_ring
        try:
            if not link.connected:
                await link.__aenter__()
            await link.send(0x030C, bytes([0, code]))
            closed = True
            await link.set_live(False)
        except Exception:
            pass
        try:
            await link.__aexit__()
        except Exception:
            pass
        totals = derived.workout_totals(first_sport, last_sport, heart)
        store.finish_workout(workout_id, totals, "done" if closed else "lost", not closed)
    return {"workout_id": workout_id, "totals": totals, "ended_by_ring": ended_by_ring}
