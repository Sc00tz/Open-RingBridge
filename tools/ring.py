#!/usr/bin/env python3
"""Health Ring test client. See ../PROTOCOL.md.

  ring.py scan [--seconds N]      listen for nearby BLE devices (no connection)
  ring.py services <address>      connect and list services (reads no values, writes nothing)
"""
import argparse
import asyncio
import sys

from bleak import BleakClient, BleakScanner

MAIN_SERVICE = "be940000-7333-be46-b7ae-689e71722bd5"
KNOWN_SERVICES = {
    MAIN_SERVICE: "main service",
    "0000ae00-0000-1000-8000-00805f9b34fb": "JieLi service",
    "6e400001-b5a3-f393-e0a9-e50e24dcca9e": "Nordic UART service",
    "0000fe59-0000-1000-8000-00805f9b34fb": "Nordic DFU service",
}
KNOWN_CHARS = {
    "be940001-7333-be46-b7ae-689e71722bd5": "C_1 write commands",
    "be940002-7333-be46-b7ae-689e71722bd5": "C_2 secondary write",
    "be940003-7333-be46-b7ae-689e71722bd5": "C_3 notify",
}


# The app's scan filter (BleHelper.filterDevice): the raw advertisement, as hex,
# contains one of these, or the device name contains "dfu".
APP_MARKERS = ("1078", "1178", "1278", "1378", "C5FE")


def advertisement_hex(adv):
    """Approximate the raw advertisement. macOS only exposes its parsed parts."""
    parts = []
    for uuid in adv.service_uuids:
        uuid = uuid.lower()
        if uuid.endswith("-0000-1000-8000-00805f9b34fb"):
            parts.append(bytes.fromhex(uuid[4:8])[::-1].hex())
        else:
            parts.append(bytes.fromhex(uuid.replace("-", ""))[::-1].hex())
    for maker, data in adv.manufacturer_data.items():
        parts.append(maker.to_bytes(2, "little").hex() + data.hex())
    for uuid, data in adv.service_data.items():
        parts.append(bytes.fromhex(uuid.lower()[4:8])[::-1].hex() + data.hex())
    return [part.upper() for part in parts]


async def scan(seconds):
    print(f"Scanning for {seconds}s. Keep the ring nearby.\n")
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    rows = sorted(found.values(), key=lambda item: item[1].rssi, reverse=True)
    matches = 0
    for device, adv in rows:
        services = [s.lower() for s in adv.service_uuids]
        name = adv.local_name or device.name or "(no name)"
        parts = advertisement_hex(adv)
        reasons = [KNOWN_SERVICES[s] for s in services if s in KNOWN_SERVICES]
        reasons += [f"app marker {m}" for m in APP_MARKERS if any(m in part for part in parts)]
        if "dfu" in name.lower():
            reasons.append("name contains dfu")
        mark = "=>" if reasons else "  "
        matches += bool(reasons)
        print(f"{mark} {adv.rssi:>4} dBm  {device.address}  {name:<28} {' / '.join(reasons)}")
        print(f"      adv: {' '.join(parts) or '-'}")
    print(f"\n{len(rows)} devices seen, {matches} matching the ring checks (marked =>).")


async def services(address):
    print(f"Connecting to {address} ...")
    async with BleakClient(address, timeout=20.0) as client:
        print(f"Connected. Reported MTU: {client.mtu_size}\n")
        for service in client.services:
            print(f"Service {service.uuid}  {KNOWN_SERVICES.get(service.uuid.lower(), '')}")
            for char in service.characteristics:
                label = KNOWN_CHARS.get(char.uuid.lower(), "")
                print(f"  Char {char.uuid}  [{','.join(char.properties)}]  {label}")
    print("\nDisconnected. Nothing was read or written.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    s = sub.add_parser("scan")
    s.add_argument("--seconds", type=float, default=12.0)
    c = sub.add_parser("services")
    c.add_argument("address")
    args = parser.parse_args()
    try:
        if args.command == "scan":
            asyncio.run(scan(args.seconds))
        else:
            asyncio.run(services(args.address))
    except Exception as err:
        print(f"Failed: {type(err).__name__}: {err}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
