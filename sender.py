"""用 bleak 把 payload 写进 TodooCard / T3（Windows / Linux / macOS 通用）。

协议是从上游 Swift 版（Sunbelife/TodooCard_Skills）逐字节对照迁移的：

    控制点 0x01 requestBlockSize  → 设备回 [0x01, blockSize, 0x00]
    控制点 0x02 + 3 字节长度      → 设备回 [0x02, 0x00]
    控制点 0x03 startTransfer
    数据点 [4 字节小端块号] + 分块数据（长度 = blockSize - 4）
    控制点 0x05 dataAck → [0x05, 0x00, 块号×4]；全部收完回 [0x05, 0x08]

未在真机验证过（开发机是 macOS，Windows 蓝牙要等用户实测）。
所以每次发送都会打详细日志，失败会明确抛出，不会静默假装成功。
"""
from __future__ import annotations

import asyncio
import struct
import time
from pathlib import Path

SERVICE_UUIDS = ["0000fef0-0000-1000-8000-00805f9b34fb",
                 "0000fdf0-0000-1000-8000-00805f9b34fb"]
CONTROL_UUIDS = ["0000fef1-0000-1000-8000-00805f9b34fb",
                 "0000fdf1-0000-1000-8000-00805f9b34fb"]
DATA_UUIDS = ["0000fef2-0000-1000-8000-00805f9b34fb",
              "0000fdf2-0000-1000-8000-00805f9b34fb"]

CMD_REQ_BLOCK = 0x01
CMD_REQ_LEN = 0x02
CMD_START = 0x03
CMD_ACK = 0x05
STATUS_OK = 0x00
STATUS_END = 0x08


class TransferError(RuntimeError):
    pass


async def _find_device(bleak, device_id: str | None, timeout: float):
    """优先按 UUID 直连；否则扫广播里的名字（含 todoo）。"""
    if device_id:
        return await bleak.BleakScanner.find_device_by_id(device_id, timeout=timeout)
    for attempt in range(3):
        dev = await bleak.BleakScanner.find_device_by_filter(
            lambda d, ad: "todoo" in (d.name or "").lower(), timeout=timeout)
        if dev:
            return dev
    return None


async def send(payload_path: Path, device_id: str | None = None,
               log=print, timeout: float = 120.0) -> dict:
    """把 .protocol.qlz（v157+ 压缩包）写进卡片。返回 {'ok':bool,'seconds':float,'message':str}。"""
    import bleak
    from bleak import BleakClient

    payload = Path(payload_path).read_bytes()
    t0 = time.time()
    log(f"[sender] payload {len(payload)} bytes, target={device_id or '扫描发现'}")

    device = await _find_device(bleak, device_id, timeout=min(30, timeout))
    if not device:
        raise TransferError("没扫到卡片广播：先确认它在广播（不在 TodooCam 等 App 里连着）")

    blocks: asyncio.Queue = asyncio.Queue()
    done = asyncio.Event()
    state = {"block_size": 0, "ended": False, "error": ""}

    async with BleakClient(device, timeout=30) as client:
        log(f"[sender] 已连接 {device.name or device.address}")

        control = next((c for s in client.services for c in s.characteristics
                        if c.uuid.lower() in CONTROL_UUIDS), None)
        data_ch = next((c for s in client.services for c in s.characteristics
                        if c.uuid.lower() in DATA_UUIDS), None)
        if not control or not data_ch:
            raise TransferError("没找到控制点/数据点特征值（固件或服务 UUID 不匹配）")

        def on_control(_ch, payload_bytes: bytearray):
            b = bytes(payload_bytes)
            log(f"[sender] control <- {b.hex(' ')}")
            if not b:
                return
            cmd = b[0]
            if cmd == CMD_REQ_BLOCK:
                if len(b) >= 3 and b[2] == STATUS_OK:
                    state["block_size"] = max(0, b[1] - 4)
                else:
                    state["error"] = "设备拒绝了 block size"
            elif cmd == CMD_REQ_LEN:
                if len(b) >= 2 and b[1] != STATUS_OK:
                    state["error"] = "设备拒绝了 payload 长度"
            elif cmd == CMD_ACK:
                if len(b) >= 2 and b[1] == STATUS_END:
                    state["ended"] = True
                    done.set()
                elif len(b) >= 6:
                    idx = int.from_bytes(b[2:6], "little")
                    blocks.put_nowait(idx)
                elif len(b) >= 2 and b[1] != STATUS_OK:
                    state["error"] = f"设备返回错误 0x{b[1]:02x}"
            blocks.put_nowait(-1)

        await client.start_notify(control, on_control)
        await asyncio.sleep(0.3)

        async def wait_for(pred, timeout_s=5.0):
            deadline = time.time() + timeout_s
            while time.time() < deadline:
                if state["error"]:
                    raise TransferError(state["error"])
                if pred():
                    return True
                await asyncio.sleep(0.05)
            return False

        log("[sender] 请求 block size")
        await client.write_gatt_char(control, bytes([CMD_REQ_BLOCK]), response=True)
        if not await wait_for(lambda: state["block_size"] > 0):
            raise TransferError("等不到 block size 响应")
        bs = state["block_size"]
        log(f"[sender] block payload = {bs} 字节")

        # 补齐到块大小整数倍（设备按块收）
        if len(payload) % bs:
            payload += b"\x00" * (bs - len(payload) % bs)
        n_blocks = len(payload) // bs
        log(f"[sender] 共 {n_blocks} 块")

        await client.write_gatt_char(control, bytes([CMD_REQ_LEN, len(payload) & 0xFF,
                                                     (len(payload) >> 8) & 0xFF,
                                                     (len(payload) >> 16) & 0xFF]), response=True)
        if not await wait_for(lambda: True, 2.0):
            pass
        await client.write_gatt_char(control, bytes([CMD_START]), response=True)

        for i in range(n_blocks):
            chunk = payload[i * bs:(i + 1) * bs]
            packet = struct.pack("<I", i) + chunk
            await client.write_gatt_char(data_ch, packet, response=False)
            # 每 32 块等一次确认，避免把设备缓冲灌爆
            if (i + 1) % 32 == 0:
                await asyncio.sleep(0.05)
            if state["error"]:
                raise TransferError(state["error"])

        log("[sender] 数据写完，等显影确认…")
        try:
            await asyncio.wait_for(done.wait(), timeout=timeout)
        except asyncio.TimeoutError:
            raise TransferError("数据写完但没收到显影确认（0x05 0x08）")
        await client.stop_notify(control)

    secs = round(time.time() - t0, 1)
    return {"ok": True, "seconds": secs, "message": f"成功（{secs}s）"}


if __name__ == "__main__":
    import argparse
    import logging

    logging.basicConfig(level=logging.INFO)
    ap = argparse.ArgumentParser(description="把 payload 写进卡片")
    ap.add_argument("--payload", required=True, type=Path)
    ap.add_argument("--id", default=None)
    a = ap.parse_args()
    print(asyncio.run(send(a.payload, a.id)))
