# Windows 安装与验证

> 状态：代码已写完，**尚未在 Windows 真机验证**。下面每步都有预期输出，对不上就把日志发出来。

## 1. 环境

- Windows 10 1803+ / Windows 11
- Python 3.10+（安装时勾选 **Add Python to PATH**）
- 蓝牙 4.0+ 适配器，系统设置里**已配对 TodooCard**

```bat
git clone https://github.com/shidaifufen/todoocard-eink.git
cd todoocard-eink
run.bat
```

浏览器打开 <http://127.0.0.1:8199/card.html>。

## 2. 先验证渲染（不需要卡片）

点左侧任意模板，右侧应出现预览图。

- 如果预览是**方块字** → 字体没找到，见第 5 节
- 如果预览报错 → 把浏览器 F12 Console 和服务窗口日志发出来

## 3. 配对与扫描

1. Windows 设置 → 蓝牙 → 添加设备 → 选择 `TodooCard` 或 `FFFF...`
2. 控制台「设备与配对」→「扫描」应能看到卡片名称
3. **发图前关掉 TodooCam 等其它连着卡片的程序**

## 4. 发送（关键一步）

点「发送这个模板」，控制台会打：

```
[sender] payload 218893 bytes, target=扫描发现
[sender] 已连接 ...
[sender] 请求 block size
[sender] control <- 01 20 00
[sender] block payload = 28 字节
[sender] 共 7818 块
[sender] 数据写完，等显影确认…
```

**预期**：卡片开始刷新，控制台显示「上次成功」。

**常见失败**：

| 日志 | 原因 | 处理 |
|---|---|---|
| `没扫到卡片广播` | 卡片没广播/被别的程序连着 | 关掉 TodooCam，长按卡片背面按钮 |
| `没找到控制点/数据点特征值` | 固件服务 UUID 不同 | 把日志里的 services 列表发出来 |
| `等不到 block size 响应` | 通知没订阅成功 | 重试一次；仍失败请提供完整日志 |
| `数据写完但没收到显影确认` | 传输中断/卡片重启 | 重试；连续失败记录块号 |
| `Access is denied` | 未配对 | 系统蓝牙里先配对 |

## 5. 字体

渲染默认按顺序找：`msyhbd.ttc` → `msyh.ttc` → `simhei.ttf`（粗体），`msyh.ttc` → `simhei.ttf` → `simsun.ttc`（常规）。

都没有的话把任意 `.ttf/.ttc` 放到 `assets/fonts/`，并在 `card_render.py` 的 `FONTS` 列表里加一行路径。

## 6. 想调试协议

```bat
python payload.py --input 某张图.png --output-prefix tmp\img
python quicklz_payload.py --input tmp\img.controller.bin --output tmp\img.protocol.qlz
python sender.py --payload tmp\img.protocol.qlz
```

`payload.py` 的产物已经和 macOS 的 Swift 转换器做过 **md5 逐字节比对**（`.bin` / `.controller.bin` / `.protocol.legacy.qlz` 三个文件全部一致），所以图像适配这块可以信任；不确定的只有 BLE 时序。
