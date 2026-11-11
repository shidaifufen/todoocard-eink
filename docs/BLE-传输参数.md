# BLE 传输参数（对照官方 App 手机端日志校准）

数据来源：2026-11-11 用官方 App（`com.sunbelife.todoocam` 187）推一张图时抓的
手机端蓝牙日志（`logcat -b all` + `dumpsys bluetooth_manager`，HCI snoop 当时是关的）。
原始文件放在 `蓝牙日志-2026-11-11/`，归纳见那边的 `蓝牙日志汇总.md`。

## 官方 App 实测到的设备特性

| 项 | 实测值 | 我们的实现 |
|---|---|---|
| 连接间隔 | 15 ms（`conn_interval=12`） | 客户端改不了，只能跟随 |
| MTU | 请求 517 → 协商 **247** | Windows 自动协商到 247，实测 `blockPayload=240` 一致 |
| PHY | 2M | Windows 自动 |
| 每块负载 | **240 字节**（247 − 3 ATT − 4 块号） | 由设备 `0x01` 响应告知，不写死 |
| 172,433 字节的图 | **719 块** | 我们的 payload 更小：文字卡 ~80 块、照片卡 ~110~210 块 |
| payload 阶段耗时 | **6.46 s ≈ 111 块/秒**（多包连发） | 实测 64~69 块/秒（`WRITE_DELAY=4ms`） |
| 逐块回执 | `05 00 <块号小端 4 字节>` | 一样 |
| 允许领先 | **16 块**（失败日志里 `window=704..<719`） | `WRITE_WINDOW = 16` |
| 显影完成 | `05 08` | 一样，等 20 s，等不到只警告 |
| 失败模式 | 3 次里 2 次卡在**最后 ~15 块**等不到 ACK（`rejectedGattWrites` ≈ 750~836） | `_tail_stalled()` 尾包兜底：只剩不到一个窗口时不判失败，改等显影确认 |
| 写入被栈拒绝 | Android 侧大量 `rejectedGattWrites` 后重试 | 写入异常重试 3 次，中间歇 150 ms |
| 失败后 | 手机本地主动断开（`HCI 0x16 CONNECTION_TERMINATED_BY_LOCAL_HOST`）后整轮重发 | 连接失败就等下一轮广播重连（总预算 `wait_card_sec`，默认 180 s） |

## 两条实践结论

1. **卡片只在广播窗口里能被连上。**
   在 Windows 上实测"按地址直连"（`BleakClient("FF:FF:99:80:39:01")`）会直接报
   `BleakDeviceNotFoundError: Device with address ... was not found`，
   必须先扫到广播再连 —— 所以发送流程是"等广播 → 立刻连"，失败再等下一轮。
2. **手机 App 和电脑不要同时用。**
   我们有一次连接超时正好落在手机传输的时间窗里（09:04:31 ~ 09:05:20），
   设备同一时间只服务一个中心设备。用电脑推之前先把手机 App 退出/断开。

## 调参位置

全在 `sender.py` 顶部的常量区：

```python
WRITE_WINDOW = 16          # 允许领先设备的块数
STALL_TIMEOUT = 10.0       # 回执停滞多久算卡死
REFRESH_WAIT = 20.0        # 等显影确认
SCAN_ROUND = 6.0           # 每轮扫描时长
CONNECT_TIMEOUT = 25.0     # 单次连接超时
WRITE_DELAY = 0.004        # 不带响应写入之间的微延时
USE_RESPONSE_WRITE = False # 是否用带响应写（慢但稳；默认流水线更快）
```

想更快就压 `WRITE_DELAY`；想更稳就开 `USE_RESPONSE_WRITE`（实测每块 ~110 ms，慢一个量级）。
要继续对细节，需要打开手机的「蓝牙 HCI 信息收集日志」
（`setprop persist.bluetooth.btsnooplogmode full`，重开蓝牙后复现一次），
拿到 `btsnoop_hci.log` 就能逐包对照了。
