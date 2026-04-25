import asyncio
import struct
import sqlite3
import logging
import os
from datetime import datetime
from bleak import BleakClient, BleakScanner

# --- LOGGING SETUP ---
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler("ring_debug.log", mode='w'),
        logging.StreamHandler()
    ]
)
logging.getLogger("bleak").setLevel(logging.WARNING)

# --- CONFIGURATION & STATE ---
DEVICE_NAME = "R01L"
YCBT_C1   = "be940001-7333-be46-b7ae-689e71722bd5"
YCBT_C3   = "be940003-7333-be46-b7ae-689e71722bd5"

status = {
    "device_name": "R01L",
    "connection":  "Disconnected",
    "last_sync":   "Never",
    "hr": "--", "bp": "--/--", "spo2": "--", "temp": "--", "stress": "--", "battery": "--",
    "wearing": "Unknown", "state": "Idle"
}

hist = {
    "pending": False, "pulling": False, "expected_bytes": 0, "received_bytes": 0,
    "ring_done": False, "buffer": b"", "client": None
}

def refresh_dashboard():
    print("\033[H\033[J", end="") 
    print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    print(f"   Smart Ring Dashboard: {status['device_name']} ({status['connection']})")
    print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    print(f"   Heart Rate: {status['hr']:<10} bpm    SpO2: {status['spo2']:<10} %")
    print(f"   Blood Pressure: {status['bp']:<18} Temp: {status['temp']:<10}")
    print(f"   Stress: {status['stress']:<18} Battery: {status['battery']:<10} %")
    print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    print(f"   Wearing: {status['wearing']:<12} State: {status['state']:<20}")
    print(f"   Last Sync: {status['last_sync']}")
    print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

# --- PROTOCOL & DB ---
def crc16(data: bytes, length: int) -> int:
    s = 0xFFFF
    for i in range(length):
        s = (((s << 8) & 0xFF00) | ((s >> 8) & 0xFF)) ^ (data[i] & 0xFF)
        s ^= (s & 0xFF) >> 4
        s ^= (s << 12) & 0xFFFF
        s ^= ((s & 0xFF) << 5) & 0xFFFF
    return s & 0xFFFF

def build_packet(datatype: int, payload: bytes = b'') -> bytes:
    total_len = len(payload) + 6
    pkt = bytearray(total_len)
    pkt[0], pkt[1] = (datatype >> 8) & 0xFF, datatype & 0xFF
    pkt[2], pkt[3] = total_len & 0xFF, (total_len >> 8) & 0xFF
    if payload: pkt[4:4+len(payload)] = payload
    crc = crc16(pkt, 4 + len(payload))
    pkt[4 + len(payload)], pkt[4 + len(payload) + 1] = crc & 0xFF, (crc >> 8) & 0xFF
    return bytes(pkt)

def ts_to_dt(raw: int) -> str:
    SEC_2001 = 946684800
    try: return str(datetime.fromtimestamp(raw + SEC_2001))
    except: return f"raw:{raw}"

class RingDatabase:
    def __init__(self, db_path="ring_data.db"):
        self.conn = sqlite3.connect(db_path)
        # 1. Base table creation
        self.conn.execute("CREATE TABLE IF NOT EXISTS history (ts DATETIME PRIMARY KEY)")
        
        # 2. Individual column safety checks
        cursor = self.conn.execute("PRAGMA table_info(history)")
        existing_cols = [row[1] for row in cursor.fetchall()]
        
        required_cols = {
            "hr": "INTEGER",
            "hrv_ms": "REAL",
            "stress": "INTEGER",
            "spo2": "INTEGER",
            "bp_sys": "INTEGER",
            "bp_dia": "INTEGER",
            "temp": "REAL",
            "sleep_type": "INTEGER"
        }
        
        for col_name, col_type in required_cols.items():
            if col_name not in existing_cols:
                logging.info(f"Adding missing column: {col_name}")
                self.conn.execute(f"ALTER TABLE history ADD COLUMN {col_name} {col_type}")
        
        self.conn.commit()

    def upsert(self, ts_str, **kwargs):
        cols = ", ".join(kwargs.keys())
        placeholders = ", ".join(["?"] * len(kwargs))
        updates = ", ".join([f"{k}=COALESCE(?, {k})" for k in kwargs.keys()])
        vals = [ts_str] + list(kwargs.values()) + list(kwargs.values())
        query = f"INSERT INTO history (ts, {cols}) VALUES (?, {placeholders}) ON CONFLICT(ts) DO UPDATE SET {updates}"
        self.conn.execute(query, vals)
        self.conn.commit()

db = RingDatabase()

# --- DECODERS ---
def decode_all_history(data):
    for i in range(0, len(data) - 19, 20):
        r = data[i:i+20]
        ts = struct.unpack_from('<I', r, 0)[0]
        if ts in [0, 0xFFFFFFFF]: continue
        hr, sbp, dbp, spo2 = r[6], r[7], r[8], r[9]
        temp = float(f"{r[13]}.{r[14]}") if r[13] > 0 else 0.0
        db.upsert(ts_to_dt(ts), hr=hr, bp_sys=sbp, bp_dia=dbp, spo2=spo2, temp=temp)
        if hr > 0: status["hr"] = f"{hr}"
        if sbp > 0: status["bp"] = f"{sbp}/{dbp}"
        if spo2 > 0: status["spo2"] = f"{spo2}"
    refresh_dashboard()

def decode_body_history(data):
    for i in range(0, len(data) - 27, 28):
        r = data[i:i+28]
        ts = struct.unpack_from('<I', r, 0)[0]
        if ts in [0, 0xFFFFFFFF]: continue
        hrv_ms = r[6] + r[7] / 10.0
        stress = r[8] * 10 + r[9]
        db.upsert(ts_to_dt(ts), hrv_ms=hrv_ms, stress=stress)
        if stress > 0: status["stress"] = f"{stress}"
    refresh_dashboard()

def decode_sleep_history(data):
    for i in range(0, len(data) - 11, 12):
        r = data[i:i+12]
        ts = struct.unpack_from('<I', r, 0)[0]
        if ts in [0, 0xFFFFFFFF]: continue
        db.upsert(ts_to_dt(ts), sleep_type=r[4])
    refresh_dashboard()

# --- HANDLERS ---
def c1_handler(sender, data):
    datatype = (data[0] << 8) | data[1]
    payload = data[4:-2]
    if datatype == 0x0200 and len(payload) >= 6:
        status["battery"] = f"{payload[5]}"
    elif 0x0500 <= datatype <= 0x057F:
        if len(payload) >= 8:
            hist["expected_bytes"] = payload[6] + (payload[7]<<8)  # all history meta uses [6:8]
            if hist["expected_bytes"] == 0: hist["ring_done"] = True
    elif datatype == 0x0580:
        hist["ring_done"] = True
        asyncio.create_task(hist["client"].write_gatt_char(YCBT_C1, build_packet(0x0580, b'\x00'), response=False))
    refresh_dashboard()

def c3_handler(sender, data):
    datatype = (data[0] << 8) | data[1]
    payload = data[4:-2]

    # Real-time packets — handle immediately, never buffer
    if datatype == 0x060A and len(payload) >= 15:
        if any(payload[:7]):  # Discard all-zero warmup packets
            if payload[7] > 0: status["hr"] = f"{payload[7]}"
            if payload[8] > 0: status["bp"] = f"{payload[8]}/{payload[9]}"
            if payload[10] > 0: status["spo2"] = f"{payload[10]}"
            status["wearing"] = "Worn" if payload[14] == 1 else "Not Worn"
            if len(payload) > 20 and payload[20] > 0:
                status["glucose"] = f"{payload[20]/10.0:.1f} mmol/L"
        refresh_dashboard()
        return
    elif datatype == 0x0613 and len(payload) >= 5:
        status["wearing"] = "Worn" if payload[4] == 1 else "Not Worn"
        refresh_dashboard()
        return
    elif datatype == 0x0603 and len(payload) >= 2:
        if payload[0] > 0:
            status["bp"] = f"{payload[0]}/{payload[1]}"
            if payload[2] > 0: status["hr"] = f"{payload[2]}"
            status["wearing"] = "Worn"
        refresh_dashboard()
        return
    elif datatype == 0x0601 and len(payload) >= 1:
        if payload[0] > 0: status["hr"] = f"{payload[0]}"
        refresh_dashboard()
        return

    # History data — buffer and decode when complete
    hist["buffer"] += payload
    if hist["ring_done"] or (hist["expected_bytes"] > 0 and len(hist["buffer"]) >= hist["expected_bytes"]):
        if datatype == 0x0518:
            decode_all_history(hist["buffer"])
            hist["buffer"] = b""
        elif datatype == 0x0534:
            decode_body_history(hist["buffer"])
            hist["buffer"] = b""
        elif datatype == 0x0505:
            decode_sleep_history(hist["buffer"])
            hist["buffer"] = b""

# --- ACTIONS ---
async def send(client, datatype, payload=b''):
    await client.write_gatt_char(YCBT_C1, build_packet(datatype, payload), response=False)
    await asyncio.sleep(0.4)

async def pull_history(client):
    if hist["pulling"]: return
    hist["pulling"] = True
    for cmd_id in [0x0509, 0x0533, 0x0505]:
        status["state"] = f"Syncing {hex(cmd_id)}"
        refresh_dashboard()
        hist["ring_done"], hist["expected_bytes"], hist["buffer"] = False, 0, b""
        await send(client, cmd_id)
        for _ in range(100):
            await asyncio.sleep(0.1)
            if hist["ring_done"]: break
    status["last_sync"] = datetime.now().strftime("%H:%M:%S")
    status["state"] = "Idle"
    hist["pulling"] = False
    refresh_dashboard()

async def keepalive_loop(client, stop_event):
    counter = 0
    while not stop_event.is_set():
        await asyncio.sleep(15)
        counter += 1
        await client.write_gatt_char(YCBT_C1, build_packet(0x032F, bytes([0x01, counter & 0xFF])), response=False)
        
        if counter % 60 == 0:
            status["state"] = "Polling Sensors..."
            refresh_dashboard()
            await send(client, 0x0211, b'\x01') 
            await send(client, 0x020E, b'\x01')
            await send(client, 0x0309, bytes([0x01, 0x00, 0x02, 0xA0]))
            await pull_history(client)

async def main():
    status["connection"] = "Scanning"
    refresh_dashboard()
    device = await BleakScanner.find_device_by_filter(lambda d, _: d.name and DEVICE_NAME in d.name)
    
    if not device:
        status["connection"] = "Not Found"
        refresh_dashboard()
        return

    async with BleakClient(device) as client:
        hist["client"] = client
        status["connection"] = "Connected"
        await client.start_notify(YCBT_C3, c3_handler)
        await client.start_notify(YCBT_C1, c1_handler)
        
        status["state"] = "Handshaking"
        refresh_dashboard()
        await send(client, 0x0225) 
        await send(client, 0x0200, b'\x47\x43') 
        
        await pull_history(client)
        await send(client, 0x0309, bytes([0x01, 0x00, 0x02, 0xA0]))  # Start real-time streaming

        stop_event = asyncio.Event()
        await keepalive_loop(client, stop_event)

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nStopped.")