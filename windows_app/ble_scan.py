"""List nearby named BLE devices; useful before first HC-08 connection."""

import asyncio

from bleak import BleakScanner


async def main() -> None:
    devices = await BleakScanner.discover(timeout=8.0, return_adv=True)
    rows = []
    for device, advertisement in devices.values():
        name = advertisement.local_name or device.name or "(unnamed)"
        services = ",".join(advertisement.service_uuids) or "-"
        rows.append((name, device.address, advertisement.rssi, services))
    for name, address, rssi, services in sorted(rows, key=lambda row: row[0].lower()):
        print(f"{name}\t{address}\t{rssi} dBm\t{services}")


if __name__ == "__main__":
    asyncio.run(main())
