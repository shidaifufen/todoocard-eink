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
            log("[ble] 读取加密电量，验证认证…")
            battery = await asyncio.wait_for(client.read_gatt_char(
                "00002a19-0000-1000-8000-00805f9b34fb"), timeout=15)
            if len(battery) != 1 or battery[0] > 100:
                raise RuntimeError("加密电量返回值无效，无法确认认证成功")
            log(f"[ble] 加密读取通过，电量 {battery[0]}%")
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


async def find_pairing_device(address, log, *, timeout=75):
    from bleak import BleakScanner

    already_paired = sys.platform == "win32" and await windows_is_paired(address)
    hinted = False

    def accept(device, adv):
        nonlocal hinted
        if device.address.upper() != address.upper():
            return False
        raw = adv.manufacturer_data.get(0x5053, b"")
        if len(raw) < 5:
            return False
        secure = raw[0] | raw[4] << 8 == 0x134C and raw[2] >= 0x8c and bool(raw[1] & 1)
        if not secure or already_paired or raw[1] & 2:
            log(f"[ble] 固件 0x{raw[2]:02X}，广播标志 0x{raw[1]:02X}，配对窗口{'已打开' if raw[1] & 2 else '已关闭'}")
            return True
        if not hinted:
            hinted = True
            log("[ble] 找到卡片，但配对窗口未开；请长按背面按钮约 5 秒直到指示灯快闪")
        return False

    return await BleakScanner.find_device_by_filter(accept, timeout=timeout)
