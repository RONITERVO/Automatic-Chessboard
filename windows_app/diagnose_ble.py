"""Connect to an HC-08 and run the non-motion 5.0 handshake over BLE."""

import argparse
import asyncio

from bleak import BleakClient, BleakScanner

from protocol import LineBuffer, hello_command
from transports import HC08_CHARACTERISTIC_UUID


async def diagnose(identifier: str, wait_seconds: float) -> int:
    lowered = identifier.lower()
    device = await BleakScanner.find_device_by_filter(
        lambda candidate, advertisement:
            candidate.address.lower() == lowered or
            (advertisement.local_name or candidate.name or "").lower() == lowered,
        timeout=12.0,
    )
    if device is None:
        print(f"Device {identifier!r} was not found")
        return 1

    lines = LineBuffer()
    received: list[str] = []
    raw_received = bytearray()

    def notification(_characteristic, data: bytearray) -> None:
        raw_received.extend(data)
        received.extend(lines.feed(bytes(data)))

    async with BleakClient(device) as client:
        print(f"CONNECTED\t{device.name}\t{device.address}")
        for service in client.services:
            print(f"SERVICE\t{service.uuid}")
            for characteristic in service.characteristics:
                print(f"  CHAR\t{characteristic.uuid}\t{','.join(characteristic.properties)}")
                if service.uuid.lower().startswith("0000180a") and "read" in characteristic.properties:
                    try:
                        value = bytes(await client.read_gatt_char(characteristic))
                        if value:
                            print(f"    VALUE\t{value!r}")
                    except Exception as error:
                        print(f"    READ_ERROR\t{error}")
        await client.start_notify(HC08_CHARACTERISTIC_UUID, notification)
        # Some HC-08 revisions need a short transparent-mode settling delay.
        await asyncio.sleep(1.0)
        print("LISTENING - press Nano RESET now", flush=True)
        deadline = asyncio.get_running_loop().time() + wait_seconds
        payload = (hello_command() + "\n").encode("ascii")
        while asyncio.get_running_loop().time() < deadline:
            await client.write_gatt_char(
                HC08_CHARACTERISTIC_UUID, payload, response=False
            )
            await asyncio.sleep(1.0)
        await client.stop_notify(HC08_CHARACTERISTIC_UUID)

    if raw_received:
        print(f"RAW\t{bytes(raw_received)!r}")
    for line in received:
        print(f"REPLY\t{line}")
    if hello_command() not in received:
        print("NO_MATCHING_HELLO")
        return 2
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("identifier", nargs="?", default="HC-08")
    parser.add_argument("--wait", type=float, default=4.0)
    args = parser.parse_args()
    return asyncio.run(diagnose(args.identifier, max(1.0, args.wait)))


if __name__ == "__main__":
    raise SystemExit(main())
