"""Framing and a small session helper for the Health Ring protocol (../PROTOCOL.md)."""
import asyncio
import datetime as dt
import json
import pathlib
import time

from bleak import BleakClient, BleakScanner

C1 = "be940001-7333-be46-b7ae-689e71722bd5"
C3 = "be940003-7333-be46-b7ae-689e71722bd5"
LOG_DIR = pathlib.Path(__file__).parent / "captures"


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
    """Return (code, payload, crc_status) for one complete frame."""
    code = (raw[0] << 8) | raw[1]
    payload = raw[4:-2]
    expected = crc16(raw[:-2])
    little = raw[-2] | (raw[-1] << 8)
    big = (raw[-2] << 8) | raw[-1]
    status = "crc-le-ok" if little == expected else "crc-be-ok" if big == expected else "crc-bad"
    return code, bytes(payload), status


class Session:
    def __init__(self, target_name="R01L C923", label="session"):
        self.target_name = target_name
        self.client = None
        self.frames = asyncio.Queue()
        self.buffer = bytearray()
        LOG_DIR.mkdir(exist_ok=True)
        stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.log_path = LOG_DIR / f"{stamp}-{label}.jsonl"
        self.log = self.log_path.open("a")

    def record(self, direction, data, uuid=""):
        self.log.write(json.dumps({"t": time.time(), "dir": direction, "uuid": uuid, "hex": bytes(data).hex()}) + "\n")
        self.log.flush()

    def _on_data(self, characteristic, data):
        self.record("rx", data, characteristic.uuid)
        self.buffer += data
        while len(self.buffer) >= 4:
            length = self.buffer[2] | (self.buffer[3] << 8)
            if length < 6:
                self.record("rx-unparsed", self.buffer)
                self.buffer.clear()
                return
            if len(self.buffer) < length:
                return
            raw = bytes(self.buffer[:length])
            del self.buffer[:length]
            self.frames.put_nowait(raw)

    async def __aenter__(self):
        device = await BleakScanner.find_device_by_filter(
            lambda d, adv: (adv.local_name or d.name or "") == self.target_name, timeout=40.0)
        if device is None:
            raise RuntimeError(f"{self.target_name} not found. Is it near the Mac and free of the phone?")
        self.client = BleakClient(device, timeout=25.0)
        await self.client.connect()
        await self.client.start_notify(C3, self._on_data)
        await self.client.start_notify(C1, self._on_data)
        return self

    async def __aexit__(self, *exc):
        try:
            await self.client.disconnect()
        finally:
            self.log.close()

    async def send(self, code, payload=b""):
        data = frame(code, payload)
        self.record("tx", data, C1)
        await self.client.write_gatt_char(C1, data, response=True)

    async def recv(self, timeout=4.0):
        return await asyncio.wait_for(self.frames.get(), timeout)

    async def ask(self, code, payload=b"", timeout=4.0):
        """Send one command and return the first reply with the same code, or None."""
        await self.send(code, payload)
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                raw = await self.recv(max(0.05, deadline - time.time()))
            except asyncio.TimeoutError:
                return None
            got, body, status = parse(raw)
            print(f"  rx {got:#06x} {status} payload={body.hex(' ') or '(empty)'}")
            if got == code:
                return body
        return None
