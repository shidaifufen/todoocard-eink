# 土豆片 · 六色墨水屏卡片工坊

面向 TodooCard / T3（528×792，六色电子墨水屏）的服务端渲染与推送工具：13 个模板、服务端 Pillow 渲染、定时推送、Web 控制台。

> 状态：**渲染与 Web 控制台可用；BLE 发送目前仅 macOS 已验证，Windows 适配进行中。**


## 直接下载就能用（推荐）

不用装 Python、不用命令行，**下载解压 → 双击 → 浏览器自动打开**。

1. 打开 [Releases](https://github.com/shidaifufen/todoocard-eink/releases) 或点绿色 `Code` → `Download ZIP`
2. 解压到任意文件夹（路径别带特殊符号）
3. 双击对应系统的一键启动：
   - Windows：`启动（Windows）.bat`
   - macOS：`启动（macOS）.command`（首次可能要右键 → 打开）
4. 浏览器会自动打开 `http://127.0.0.1:8199/card.html`

第一次运行会自动创建独立环境并安装依赖（需要联网，约 1 分钟），之后秒开。
如果提示找不到 Python，脚本会帮你打开下载页——**安装时记得勾选 "Add Python to PATH"**。

## 能做什么

- **13 个模板**：每日总结 / 待办 / 天气 / 月历 / 每日一句 / 诗词（带赏析）/ 时间进度 / 充电统计 / 倒计时 / 照片轮播 / Bing 壁纸 / AI 生图 / 自定义积木
- **服务端渲染**：Pillow 直出 528×792，只用调色板里那 6 个精确色，文字经过抖动仍是实的
- **Web 控制台**：左侧模板 + 右侧设置，所见即所得预览，字号 100%~130% 全局缩放
- **定时计划**：按 cron 式时间点生成并推送，失败进重试队列，卡片一广播就补发
- **照片卡**：EXIF 拍摄时间/设备/焦段光圈快门 ISO，GPS 离线反查城市（内置 206 城）


## 发布（Release）约定

**每个 Release 都必须挂一个「解压即用」的程序包**，用户不需要 Python、不需要联网装依赖。

| 平台 | 产物 | 打包命令 |
|---|---|---|
| Windows | `todocard-portable-<版本>.zip` | `pwsh -File packaging\build.ps1`（Windows 上跑） |
| macOS | `todoocard-eink-<版本>-darwin-<arch>.zip` | `bash packaging/build_release.sh <版本>`（macOS 上跑，需先 `pip install pyinstaller`） |

macOS 包内结构：

```
todoocard-eink-1.0.10-darwin-arm64/
├── TodooCardEink/          ← PyInstaller 目录版（启动 1~2s）
├── start-mac.command       ← 双击启动（英文名，防解压乱码）
├── 启动（macOS）.command
└── 使用说明.txt
```

启动后浏览器自动打开 `http://127.0.0.1:8199/card.html`；数据写在包内 `data/`，删目录即卸载。

> 注意：macOS 包只能在打包它的架构上跑（arm64 / x86_64 分开打）；Windows 包要在 Windows 上打。

## 快速开始

```bash
./run.command          # macOS / Linux
run.bat                # Windows
```

打开 <http://127.0.0.1:8199/card.html>。

## 需要你自己准备的

1. **卡片设备**：先在系统蓝牙里配对 TodooCard（否则会报 `Peer removed pairing information`）
2. **发送通道**：
   - macOS：需要 `data/bin/mac-image-transfer`（Swift 编译，见上游 `Sunbelife/TodooCard_Skills`）
   - Windows：暂未提供，见下方「Windows 适配」
3. **字体**：现在用系统中文字体（macOS 用 `STHeiti`/`PingFang`）。Windows 会自动找 `msyh.ttc`，如果渲染出方块，把字体放到 `assets/fonts/` 并在 `card_render.py` 的 `FONT_CANDIDATES` 里加路径

## Windows 适配进度

| 项 | 状态 |
|---|---|
| Web 服务 / 渲染 | ✅ 已可运行（与平台无关） |
| 选文件夹 | ⬜ 待做：Windows 用 `tkinter.filedialog` 或 PowerShell `FolderBrowserDialog` |
| 发送（BLE 写入） | ⬜ 待做：Swift 二进制只有 macOS 版，需要用 `bleak` 重写分包/确认逻辑 |
| 字体 | ⬜ 待验证：`msyh.ttc` 下的字宽与 macOS 不同，模板可能需微调 |

## 六色与抖动

调色板只有：黑 `(0,0,0)`、白、黄 `(255,255,0)`、红 `(255,0,0)`、蓝 `(0,0,255)`、绿 `(0,255,0)`。

**文字必须用精确的调色板色**，Floyd–Steinberg 的误差才为 0；用近似色会被抖动成噪点。照片才需要真正的抖动。

## 目录

```
card.py           发送队列 / 定时计划 / 照片池 / AI
card_render.py    13 个模板的绘制
server.py         aiohttp 壳（页面 + /api/card*）
web/              card.html（控制台）scenes.html（场景广场）
data/cities.json  离线城市库
```

## 许可

MIT。上游思路参考 `XDS9314/todoocard` 与 `Sunbelife/TodooCard_Skills`，协议实现请遵循其许可证。
