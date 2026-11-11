"""用 bleak 把 payload 写进 TodooCard / T3（Windows / Linux / macOS 通用）。

协议（与上游 Swift、网页版 js/ble.js 一致）：

    控制点 0x01 requestBlockSize  → 设备回 [0x01, blockSize, 0x00]
    控制点 0x02 + 3 字节长度      → 设备回 [0x02, 0x00]
    控制点 0x03 startTransfer
    数据点 [4 字节小端块号] + 分块数据（长度 = blockSize - 4）
    控制点 0x05 逐块回执 [0x05, 0x00, 块号×4 小端]；全部收完回 [0x05, 0x08]

发送流程（这一版按真机表现重写过）：

  1. 等卡片广播：卡片平时不常广播，扫不到就继续等，不要求用户按键
  2. 连不上就等下一轮广播再连（真机上"看到广播"和"连上"之间可能差一拍，
     单次 connect 超时后重试比直接报错靠谱）
  3. 握手：0x01 问块大小 → 0x02 报长度 → 0x03 开始
  4. 数据阶段：多包连发（不带响应写 + 微延时），用设备逐块回执做滑窗流控
     （领先超过 WRITE_WINDOW 块就等）；写入被蓝牙栈拒绝会重试
  5. 尾包兜底：只剩不到一个窗口没回执时不当失败，改等显影确认（官方 App
     在最后 ~15 块也等不到逐块 ACK，但画面照样刷）
  6. 等显影确认 0x05 0x08；等不到只警告，不误报失败

节奏参数来自官方 App（todoocam 187）的手机端蓝牙日志：
MTU 247 / PHY 2M / 连接间隔 15ms / blockPayload 240 / 172KB 分 719 块 / 6.46s ≈ 9ms/块。

下面的常量是调参入口，真机协议日志回来后改这里即可，不用动流程。
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

# ── 可调参数 ─────────────────────────────────────────────────────────
# 依据官方 App（todoocam 187）的手机端蓝牙日志实测：
#   MTU 247 / PHY 2M / 连接间隔 15ms / blockPayload 240 / 172KB 分 719 块
#   逐块回执 05 00 <块号>，允许领先 16 块；719 块 6.46s ≈ 9ms/块（多包连发）
WRITE_WINDOW = 16          # 允许领先设备的块数（官方日志里 window=704..<719）
STALL_TIMEOUT = 10.0       # 回执停滞多久算卡死
REFRESH_WAIT = 25.0        # 等显影确认 0x05/0x08 的时长
SCAN_ROUND = 6.0           # 每轮扫描时长
SCAN_PAUSE = 1.0           # 两轮扫描之间歇一下
CONNECT_TIMEOUT = 8.0      # 单次连接超时（短一点，好在广播窗口里多试几次）
CONNECT_RETRY_PAUSE = 1.2  # 两次连接之间歇一下
CONNECT_ATTEMPTS = 3       # 看到广播后原地连续重试次数（第一次常用来热链路）
USE_RESPONSE_WRITE = False # 数据块用 withResponse（设备不支持会自动退回）
WRITE_DELAY = 0.004        # 不带响应写入之间的微延时，别把蓝牙栈队列撑爆


class TransferError(RuntimeError):
    pass


async def _scan_once(bleak, device_id: str | None):
    """扫一轮，找目标卡片。

    优先按绑定的地址匹配；但卡片地址可能会轮换，所以也接受
    "广播里带厂商数据 0x5053"（TodooCard 的 company id）或名字带 todoo 的设备。
    """
    devs = await bleak.BleakScanner.discover(timeout=SCAN_ROUND, return_adv=True)
    target = (device_id or "").upper()
    fallback = None

    def _tag(dev, adv):
        # 把广播里的 rssi 挂到设备对象上，连接日志里能看到信号强度
        try:
            dev._adv_rssi = getattr(adv, "rssi", None)
        except Exception:
            pass
        return dev

    for addr, (dev, adv) in devs.items():
        name = dev.name or adv.local_name or ""
        if target and str(addr).upper() == target:
            return _tag(dev, adv)
        if fallback is None:
            if 0x5053 in (adv.manufacturer_data or {}):
                fallback = _tag(dev, adv)
            elif "todoo" in name.lower():
                fallback = _tag(dev, adv)
    return fallback


async def _wait_for_device(bleak, device_id: str | None, timeout: float, log):
    """等卡片广播。卡片平时不常广播，扫不到就继续等，不折腾用户按键。"""
    deadline = time.time() + max(SCAN_ROUND, float(timeout))
    attempts = 0
    while time.time() < deadline:
        attempts += 1
        try:
            dev = await _scan_once(bleak, device_id)
        except Exception as e:  # 适配器忙的时候扫描会抛，继续等
            log(f"[sender] 扫描出错（第 {attempts} 轮）：{e}")
            dev = None
        if dev:
            rssi = getattr(dev, "_adv_rssi", None)
            log(f"[sender] 看到卡片：{dev.name or getattr(dev, 'address', '?')}"
                + (f"（rssi {rssi}）" if rssi is not None else ""))
            return dev
        left = int(deadline - time.time())
        if left <= 0:
            break
        log(f"[sender] 还没等到卡片广播，继续等（剩 {left}s）…")
        await asyncio.sleep(SCAN_PAUSE)
    raise TransferError(
        f"等了 {int(timeout)} 秒没看到卡片广播：确认蓝牙开着、卡片有电、没被 TodooCam 等 App 占着")


async def _verify_and_diag(client, log):
    """连接后立刻确认链路真的可用，并读一下卡片电量。

    真机日志里出现过"已连接"之后马上 disconnected / "Could not start notify: Unreachable"：
    connect() 返回时链路其实已经断了（卡片休眠/电量低/信号弱）。在这里提前发现并重连，
    比等到订阅/写入时才失败靠谱。
    """
    try:
        _ = [s for s in client.services]        # 触发/读取服务缓存；链路断了会抛
    except Exception as e:
        log(f"[sender] 连上但链路不可用（{type(e).__name__}），重连…")
        return None
    batt = None
    try:
        for s in client.services:
            for c in s.characteristics:
                if c.uuid.lower().startswith("00002a19"):   # Battery Level
                    v = await asyncio.wait_for(client.read_gatt_char(c), 4.0)
                    if v:
                        batt = int(v[0])
                    break
            if batt is not None:
                break
    except Exception as e:
        log(f"[sender] 读电量失败（{type(e).__name__}），继续发送")
    if batt is not None:
        log(f"[sender] 卡片电量 {batt}%" + ("（偏低，建议先充电）" if batt < 20 else ""))
    return batt if batt is not None else -1


async def _connect(bleak, device_id: str | None, timeout: float, log):
    """等广播 → 连接。

    真机日志里的现象：卡片一直在广播，但每次"连一下就超时"（25s 都不握手），
    连 4 次全挂。上游 macOS 版也遇到过同样的情况（日志原文
    "Timed out preparing the verified TodooCard GATT connection"），它的对策是
    **先 probe 一次把加密链路/服务缓存热起来，紧接着再正式连**。

    所以这里改成：看到广播后**立刻原地连续重试几次**（不等下一轮广播），
    并且在指定服务 UUID 的情况下连接（跳过全量服务发现，握手更短更稳）。
    """
    from bleak import BleakClient

    deadline = time.time() + max(SCAN_ROUND, float(timeout))
    last = ""
    log(f"[sender] 等广播（总预算 {int(timeout)}s）…")
    while time.time() < deadline:
        left = deadline - time.time()
        device = await _wait_for_device(bleak, device_id, left, log)
        name = device.name or getattr(device, "address", "?")
        rssi = getattr(device, "rssi", None)
        log(f"[sender] 尝试连接 {name}" + (f"（rssi {rssi}）" if rssi is not None else ""))
        # 同一台设备连续试。实测：按"扫描到的对象"连经常卡超时，按地址重建 client
        # 有时能连上但连上就断；所以两种都试，而且**连上后必须验证链路可用**
        # （get services + 读电量），验证不过就当这次连接失败，继续重试。
        targets = [device]
        addr = getattr(device, "address", None) or (device_id or None)
        if addr:
            targets.append(str(addr))
        for attempt in range(CONNECT_ATTEMPTS):
            if time.time() >= deadline:
                break
            for target in targets:
                remain = max(4.0, min(CONNECT_TIMEOUT, deadline - time.time()))
                client = None
                kind = "对象" if not isinstance(target, str) else "地址"
                try:
                    try:
                        client = BleakClient(target, timeout=remain, services=SERVICE_UUIDS)
                    except TypeError:      # 老版本 bleak 不认 services
                        client = BleakClient(target, timeout=remain)
                    await client.connect()
                    ok = await _verify_and_diag(client, log)
                    if ok is not None:
                        log(f"[sender] 已连接并验证通过（{kind}）")
                        return client, device
                    last = "连上后链路不可用"
                    try:
                        await client.disconnect()
                    except Exception:
                        pass
                    continue
                except Exception as e:
                    last = f"{type(e).__name__}: {e!r}"
                    log(f"[sender] 第 {attempt + 1}/{CONNECT_ATTEMPTS} 次（{kind}）失败："
                        f"{type(e).__name__}，马上重试…")
                    try:
                        if client is not None:
                            await client.disconnect()
                    except Exception:
                        pass
                    await asyncio.sleep(CONNECT_RETRY_PAUSE / 2)
        log("[sender] 这台暂时连不上，继续等下一轮广播…")
    raise TransferError(
        "连不上卡片：能扫到广播但握不上手或连上就断。"
        "常见原因：卡片电量低/进入休眠、离电脑太远、Windows 配对信息失效、或手机 App 还占着——"
        "先把卡片靠近并充电，退出手机 TodooCam；仍不行就到 Windows 设置 → 蓝牙 移除这台卡重新配对")


async def send(payload_path: Path, device_id: str | None = None,
               log=print, timeout: float = 180.0) -> dict:
    """把 .protocol.qlz（v157+ 压缩包）写进卡片。

    返回 {'ok','seconds','message','acked','blocks'}；
    acked=False 表示数据写完了但没等到显影确认（画面可能已经刷新）。
    """
    import bleak

    payload = Path(payload_path).read_bytes()
    t0 = time.time()
    log(f"[sender] payload {len(payload)} bytes, target={device_id or '扫描发现'}")

    client, device = await _connect(bleak, device_id, timeout, log)

    state = {"block_size": 0, "acked": -1, "ended": False, "error": "",
             "last_ack": time.time()}
    ended = asyncio.Event()

    def on_control(_ch, payload_bytes):
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
                ended.set()
            elif len(b) >= 6:
                idx = int.from_bytes(b[2:6], "little")
                if idx > state["acked"]:
                    state["acked"] = idx
                state["last_ack"] = time.time()
            elif len(b) >= 2 and b[1] != STATUS_OK:
                state["error"] = f"设备返回错误 0x{b[1]:02x}"

    n_blocks = 0
    acked_end = False
    try:
        control = next((c for s in client.services for c in s.characteristics
                        if c.uuid.lower() in CONTROL_UUIDS), None)
        data_ch = next((c for s in client.services for c in s.characteristics
                        if c.uuid.lower() in DATA_UUIDS), None)
        if not control or not data_ch:
            raise TransferError("没找到控制点/数据点特征值（固件或服务 UUID 不匹配）")

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
            raise TransferError("等不到 block size 响应（通知没订阅成功？）")
        bs = state["block_size"]
        log(f"[sender] block payload = {bs} 字节")

        # 补齐到块大小整数倍（设备按块收）
        if len(payload) % bs:
            payload += b"\x00" * (bs - len(payload) % bs)
        n_blocks = len(payload) // bs
        log(f"[sender] 共 {n_blocks} 块")

        await client.write_gatt_char(control,
                                     bytes([CMD_REQ_LEN, len(payload) & 0xFF,
                                            (len(payload) >> 8) & 0xFF,
                                            (len(payload) >> 16) & 0xFF]),
                                     response=True)
        await asyncio.sleep(0.2)
        await client.write_gatt_char(control, bytes([CMD_START]), response=True)

        # ── 数据阶段：流水线写 + 逐块回执滑窗流控（对齐官方 App 的节奏）──
        # 官方 App 172KB/719 块只用 6.46s（≈9ms/块），说明它不是"一包一等 ATT 确认"，
        # 而是多包连发、用设备回执控窗。所以这里默认用不带响应的写 + WRITE_DELAY 微延时，
        # 真正的限速交给设备回执（window=16）。
        response_write = USE_RESPONSE_WRITE
        stalled_tail = False

        async def write_block(idx: int) -> None:
            nonlocal response_write
            packet = struct.pack("<I", idx) + payload[idx * bs:(idx + 1) * bs]
            for attempt in range(3):
                try:
                    if response_write:
                        await client.write_gatt_char(data_ch, packet, response=True)
                    else:
                        await client.write_gatt_char(data_ch, packet, response=False)
                        await asyncio.sleep(WRITE_DELAY)
                    return
                except Exception as e:
                    low = str(e).lower()
                    switchable = ("not support" in low or "not permitted" in low
                                  or "invalid" in low or "rejected" in low)
                    if switchable:
                        response_write = not response_write
                        log(f"[sender] 写入方式切到 "
                            f"{'带响应' if response_write else '不带响应'}（{e}）")
                        continue
                    if attempt >= 2:
                        raise
                    # 蓝牙栈队列忙（Android 侧叫 rejectedGattWrites），歇一下重发
                    log(f"[sender] 第 {idx} 块写入被拒（{e}），稍后重试")
                    await asyncio.sleep(0.15)

        def _tail_stalled() -> bool:
            """尾部兜底：只剩不到一个窗口没回执，就别硬判失败，交给显影确认。

            官方日志里两次失败都是卡在最后 ~15 块（window=704..<719），
            说明设备到最后可能不回逐块 ACK，但画面照样刷。
            """
            return state["acked"] >= n_blocks - 2 - WRITE_WINDOW

        t_data = time.time()
        for i in range(n_blocks):
            if state["error"]:
                raise TransferError(state["error"])
            # 领先设备太多就等回执（真正的流控）
            while (i - state["acked"]) > WRITE_WINDOW and not state["ended"]:
                if state["error"]:
                    raise TransferError(state["error"])
                if time.time() - state["last_ack"] > STALL_TIMEOUT:
                    if _tail_stalled():
                        stalled_tail = True
                        log(f"[sender] 尾包回执没来（设备回到第 {state['acked'] + 1} 块，"
                            f"共 {n_blocks} 块），改等显影确认")
                        break
                    raise TransferError(
                        f"设备回执停滞：已写 {i}/{n_blocks} 块，设备只回到第 "
                        f"{state['acked'] + 1} 块，{STALL_TIMEOUT:.0f} 秒没进展")
                await asyncio.sleep(0.01)
            if stalled_tail or state["ended"]:
                break
            await write_block(i)
            if (i + 1) % 128 == 0:
                rate = (i + 1) / max(0.001, time.time() - t_data)
                log(f"[sender] 已发送 {i + 1}/{n_blocks} 块"
                    f"（设备回执到 {state['acked'] + 1}，{rate:.0f} 块/秒）")
            if time.time() - t0 > 600:
                raise TransferError(f"传输超时：已写 {i}/{n_blocks} 块")

        if n_blocks and not stalled_tail:
            rate = n_blocks / max(0.001, time.time() - t_data)
            log(f"[sender] {n_blocks} 块写完，{rate:.0f} 块/秒（官方 App 约 111 块/秒）")

        log("[sender] 数据写完，等显影确认…")
        try:
            await asyncio.wait_for(ended.wait(), timeout=REFRESH_WAIT)
            acked_end = True
            log("[sender] 收到显影确认（0x05 0x08）")
        except asyncio.TimeoutError:
            log(f"[sender] {REFRESH_WAIT:.0f}s 内没收到显影确认：画面可能已经刷新了，先当成功")
        try:
            await client.stop_notify(control)
        except Exception:
            pass
    finally:
        try:
            await client.disconnect()
        except Exception:
            pass

    secs = round(time.time() - t0, 1)
    msg = f"成功（{secs}s）" if acked_end else f"已写入，未收到显影确认（{secs}s）"
    return {"ok": True, "seconds": secs, "message": msg, "acked": acked_end,
            "blocks": n_blocks}


if __name__ == "__main__":
    import argparse
    import logging

    logging.basicConfig(level=logging.INFO)
    ap = argparse.ArgumentParser(description="把 payload 写进卡片")
    ap.add_argument("--payload", required=True, type=Path)
    ap.add_argument("--id", default=None)
    ap.add_argument("--wait", type=float, default=180.0, help="等卡片广播的秒数")
    a = ap.parse_args()
    print(asyncio.run(send(a.payload, a.id, timeout=a.wait)))
