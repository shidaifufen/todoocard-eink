"""System pairing before GATT discovery; never initiate two pairing requests."""
import asyncio
import sys


async def windows_is_paired(address):
    from winrt.windows.devices.bluetooth import BluetoothLEDevice
    from winrt.windows.devices.enumeration import DeviceInformation

    device = await BluetoothLEDevice.from_bluetooth_address_async(
        int(address.replace(":", ""), 16))
    if device is None:
        return False
    try:
        info = await DeviceInformation.create_from_id_async(device.device_information.id)
        return bool(info.pairing.is_paired)
    finally:
        device.close()


async def pair_device(device, log, *, client_factory=None, paired_check=None,
                      wait_seconds=20, poll_seconds=1):
    from bleak import BleakClient
    factory = client_factory or BleakClient
    check = paired_check or windows_is_paired

    async def connect(pair):
        client = factory(device, pair=pair, timeout=45)
        try:
            # Bleak pairs before discovering services. Connecting first and then
            # calling pair() can overlap Windows' authentication procedure.
            await client.connect()
            if not client.is_connected:
                raise RuntimeError("配对后设备未连接")
        finally:
            try:
                await asyncio.wait_for(client.disconnect(), timeout=5)
            except Exception:
                pass

    try:
        await connect(True)
    except Exception as exc:
        if sys.platform != "win32" or "OPERATION_ALREADY_IN_PROGRESS" not in str(exc):
            raise
        log("[ble] Windows 已有配对操作，等待系统确认结果（不重复发起配对）…")
        deadline = asyncio.get_running_loop().time() + wait_seconds
        while asyncio.get_running_loop().time() < deadline:
            if await asyncio.wait_for(check(device.address), timeout=5):
                log("[ble] Windows 已确认配对，验证连接…")
                await connect(False)
                return
            await asyncio.sleep(poll_seconds)
        raise RuntimeError("Windows 的上一轮配对仍未完成，未确认配对成功；请关闭其他连接并让卡片重新进入配对窗口") from exc
