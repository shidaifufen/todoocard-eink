# 安卓端交接说明（2026-11-23）

换电脑继续开发时，看这一份就够。

### 2026-11-25 后续更新 1.5.2

修复桌面端配对后的加密 GATT 准备，先读取加密电量并让服务缓存稳定，再订阅控制通知和请求分块。真实卡片验证完成图片发送及 0x05/0x08 显影确认。安卓版本号 1.5.2（versionCode 9），复用已有固定签名，保持覆盖安装路径。

### 2026-11-24 后续更新 1.5.0

版式与本地内容分离，见 [版式包格式与使用](layouts/README.md) 和 [Android 1.5.0](docs/ANDROID-1.5.0.md)。两种天气卡片完整使用 JSON；以后布局修改优先提供独立 JSON 供用户导入。所有文字卡片可用通用组件覆盖，默认的复杂卡片暂以 builtin 兼容组件保留。设置 → 外观支持预览、导入/导出、撤销与恢复。布局配置不包括本地内容和密钥，传输实现保持原样。

### 2026-11-24 后续更新 1.4.0

见 [Android 1.4.0 更新与限制](docs/ANDROID-1.4.0.md)。天气图示与曲线、后台缓存刷新、自由组合、排版控制、照片地点备注和图库预览收起。荣耀专属展示仍需真机与厂商接入验证。

### 2026-11-24 后续更新 1.3.0

见 [Android 1.3.0 更新与 Web 功能核对](docs/ANDROID-1.3.0.md)。默认 API 内容、自动城市天气、系统相册目录、精确定时与实时通知，协议层保持不变。

### 2026-11-24 安卓交互更新

- 新版版本号 1.2.0（versionCode 3），发布标签 `v1.2.0-android`。
- 调色页新增可开关的杂色吸附与强度（1–100，推荐 60）：沿用桌面 `snap_palette` 的灰阶黑白吸附和邻近六色吸附规则，默认关闭；新选/分享照片与计划轮播沿用设置。
- 吸附为渲染阶段的预处理，停下调整后异步生成吸附预览；不改动量化、压缩、BLE 传输算法。
- 多图分享批量导入轮播并自动推送第一张，临时授权照片保存至应用私有目录；系统照片选择器持久授权失败时自动复制作为后备。
- 裁切与调色参数自动保存；文件夹轮播中移除的照片不再被重新枚举回来。
- 卡片页新增月历、天气看板；总结、诗词、长文本和进度布局已调整。
- 天气可通过城市搜索获取 Open-Meteo 天气与美标 AQI；计划会刷新已选择城市的数据。
- 系统照片选择器支持多选并持久授权，照片与 SAF 文件夹共用轮播来源。
- 照片轮播提供抽一张、预览、编辑、直接推送，卡片页照片模板也提供抽图。
- 照片编辑复用解码图实时绘制，分裁切/调色/信息带，并处理 EXIF 旋转。
- 图片分享进入应用后直接发起推送；冷启动和 onNewIntent 都支持。
- 手动推送由前台服务持有，系统通知展示传输进度；Android 16+ 尝试请求实时状态提升。
- 未修改 `BleClient.kt`、`Payload.kt`、`QuickLz.kt` 的传输实现。
- CI 导出 `android-card-previews`，包含模板和照片裁切验证图片。
- 厂商胶囊展示、相册提供方授权和手势流畅度仍需真机验证。

---

## 一、仓库与产物

| 项 | 地址 |
|---|---|
| 仓库 | https://github.com/shidaifufen/todoocard-eink |
| 安卓源码 | `android/`（Kotlin + Jetpack Compose） |
| 安卓 CI | `.github/workflows/build-android.yml` |
| 最新正式包 | https://github.com/shidaifufen/todoocard-eink/releases/download/v1.0.0-android/todocard-android-1.0.0.apk |

构建方式：**不本地构建**，push 到 `main` 即触发 GitHub Actions，产物在 run 的 Artifacts 里（`app-debug.apk`）。

```bash
# 拉最新构建
export GH_TOKEN=<你的 token，需要 contents + workflows 权限>
RID=$(curl -s -H "Authorization: Bearer $GH_TOKEN" \
  "https://api.github.com/repos/shidaifufen/todoocard-eink/actions/workflows/build-android.yml/runs?per_page=1" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['workflow_runs'][0]['id'])")
AID=$(curl -s -H "Authorization: Bearer $GH_TOKEN" \
  "https://api.github.com/repos/shidaifufen/todoocard-eink/actions/runs/$RID/artifacts" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['artifacts'][0]['id'])")
curl -sL -H "Authorization: Bearer $GH_TOKEN" \
  "https://api.github.com/repos/shidaifufen/todoocard-eink/actions/artifacts/$AID/zip" -o a.zip
unzip -o a.zip   # 得到 app-debug.apk
```

> 注意：仓库里**不要**提交 token。推送用 `https://x-access-token:$GH_TOKEN@github.com/...`，推完立刻改回干净 URL。

---

## 二、安卓工程结构

```
android/app/src/main/java/com/shidaifufen/todocard/
├── MainActivity.kt     四个 Tab 的状态、权限与推送流程
├── StudioUi.kt         卡片工作台、完整预览、模板内容编辑
├── LibraryUi.kt        相册、照片调整、设备与设置界面
├── PlanScreen.kt       每日计划、间隔推送、时间轴与历史
├── Render.kt           528×792 六色渲染器（summary/quote/todo/progress/countdown/poem/custom）
├── PhotoCard.kt        照片卡：cover 裁切 + EXIF 信息带（日期/时间/机身/参数）
├── PhotoEdit.kt        照片编辑渲染：缩放/位移/亮度/对比度/饱和度 → 528×792
├── AlbumStore.kt       SAF 相册枚举（jpg/png/webp/heic，递归 3 层，上限 500）
├── Payload.kt          4bpp 量化 → 面板标定（rot180+flipH）→ 控制器视角（rotateCW+mirror）→ legacy 包
├── QuickLz.kt          QuickLZ level-3 压缩（2048 块、0x7D/0x7C 头）
├── BleClient.kt        BLE：扫描/连接/握手/数据传输/等显影确认
├── PlanStore.kt        SharedPreferences 存储：计划、间隔、历史、设备、相册、照片、模板内容
├── Scheduler.kt        WorkManager 每 15 分钟检查计划并推送
├── PushService.kt      前台服务（connectedDevice）
└── PushWorker.kt       备用 WorkManager worker
```

### 关键常量（协议）

| 项 | 值 |
|---|---|
| 服务 UUID | `0000FEF0-…` / `0000FDF0-…` |
| 控制点 | `FEF1` / `FDF1` |
| 数据点 | `FEF2` / `FDF2` |
| 握手 | `01 00` 请求块大小 → 设备回 `01 F4 00` → payload 240 |
| 长度 | `02` + 4 字节 LE + `01` |
| 开始 | `03` |
| 数据包 | `[uint32 LE idx] + 240 字节`，共 244 字节 |
| 回执 | `05 00 <idx LE32>`（设备约每 32 块一次） |
| 显影 | `05 08` |

> 以上全部来自官方 App 的 HCI 抓包 + 桌面版 `sender.py` 双向验证。

---

## 三、真机调试流程（自己跑，不用问用户）

环境：macOS + `/tmp/platform-tools/adb`，设备 `LDY-AN00`（Android 17 / API 37）。

```bash
cd /tmp
./platform-tools/adb devices
./platform-tools/adb install -r app-debug.apk     # 重装（签名不同需先 uninstall）

# 装完会弹“继续安装”，点它：
./platform-tools/adb shell uiautomator dump /sdcard/u.xml
./platform-tools/adb shell cat /sdcard/u.xml | grep 继续安装   # 拿到 bounds
./platform-tools/adb shell input tap 880 2515
```

界面坐标（设备 1216×2640）：

| 目标 | 坐标 |
|---|---|
| 卡片 Tab / 相册 / 计划 / 设置 | (152,2500) / (485,2500) / (631,2500) / (1081,2500) |
| 扫描 | (631,1199) |
| 推送 | (869,1224) |
| 权限“始终允许” | (850,2530) |

### 抓 HCI（蓝牙 sniffer）

```bash
./platform-tools/adb shell su -c "ls -t /data/log/bt/*.log | head -1"   # 设备已 root
./platform-tools/adb shell su -c "cp <log> /sdcard/x.log"
./platform-tools/adb pull /sdcard/x.log /tmp/x.log
```

日志格式 btsnoop：16 字节头 + 每包 `24 字节头 + HCI 包`；`payload[0]==0x02` 为 ACL，L2CAP CID 4 = ATT。
解析脚本思路见本文件的“抓包结论”。

### 注入测试照片（验证照片卡/编辑页）

```bash
./platform-tools/adb push test.jpg /data/local/tmp/t.jpg
./platform-tools/adb shell run-as com.shidaifufen.todocard mkdir -p files/photos shared_prefs
./platform-tools/adb shell "run-as com.shidaifufen.todocard sh -c 'cp /data/local/tmp/t.jpg files/photos/t.jpg'"
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n    <string name="lastPhoto">/data/user/0/com.shidaifufen.todocard/files/photos/t.jpg</string>\n</map>\n' > plan.xml
./platform-tools/adb push plan.xml /data/local/tmp/plan.xml
./platform-tools/adb shell "run-as com.shidaifufen.todocard sh -c 'cp /data/local/tmp/plan.xml shared_prefs/plan.xml'"
```

---

## 四、已完成 & 已验证

| 模块 | 状态 | 验证方式 |
|---|---|---|
| 8 个模板渲染 | ✅ | 真机截图逐个模板 |
| 照片卡（信息带） | ✅ | 注入照片 + 截图 |
| 照片编辑（拖拽/缩放/调色） | ✅ | 滑动前后像素 diff + 截图 |
| 相册（SAF + 缩略图） | ✅ | 代码 + UI 渲染 |
| 计划/时间轴/历史 | ✅ | 真机截图 |
| 配对 + 等广播推送 | ✅ | 真机扫码 + 用户确认推送成功 |
| Android 系统分享 | ✅ | 接收图片与文字 Intent |
| 荣耀任意门服务 | 待接入 | 需获取厂商服务接入规范，并在 MagicOS 11 验证服务显示及图片交付 |
| QuickLZ 与桌面版一致 | ✅ | CI 逐字节断言（`raw.bin` vs `expected.qlz`） |
| BLE 传输 | ✅ | 用户确认推送成功；协议按官方抓包对齐 |

---

## 五、抓包结论（重要，别再重新推导）

官方 App 一次完整推送（`btsnoop` 解析）：

```
35.0s  → 01 00                    请求块大小
34.2s  ← c0 00 ff ff
77.9s  → 01
77.9s  ← 01 f4 00                 blockSize=0xF4=244 → payload 240
77.9s  → 02 00 b2 02 00 01        长度：4 字节 LE + 0x01
78.0s  → 03                       开始
78.4s  → 244B × 713 次            数据，中位间隔 4.08ms，Write Command(0x52)
       ← 05 00 00 00 00 00        每 32 块一个 checkpoint
       ← 05 00 20 00 00 00 …
92.1s  ← 05 08 00 00 00 00        显影确认（最后一包后约 7.5s）
```

排查过程中踩过的坑，别再重复：

1. **设备只确认第 0 块** → 多半是写入被 Android GATT 队列静默丢弃，必须退避重试
2. **块号乱序** → 不要在写失败时 `sent++`，块号必须以设备回执为准
3. **不要用 Write Request(0x12)** 发数据块，官方全程 `0x52`
4. **握手漏字节**：`01 00` 必须带尾随 `00`；长度命令必须 4 字节 + `01`
5. 压缩包首块头应为 `00 00 00 00 00 00 00 00 7f …`

---

## 六、剩余 TODO（按优先级）

1. **看板元素与 Web 版对齐**：天气三天预报、AQI、诗句赏析排版；对照 `web/card.html` 与桌面版 `card_render.py`
2. **导航重构**：已完成卡片预览与底部操作条，设置页已拆「设备 / 外观 / 关于」
3. **相册轮播入计划**：`Scheduler.kt` 已从相册随机取图；仍需真机验证轮播效果
4. **收藏**：把满意的卡片存为收藏，一键重推
5. **桌面 / Web 文档版本号同步**

---

## 七、桌面端速查（同一仓库）

```bash
# macOS 桌面版
.venv/bin/python card.py            # 本地卡片控制台
.venv/bin/python server.py          # Web 控制台 http://127.0.0.1:8765

# 打包
packaging/ 下脚本；Windows 包走 workflow build-windows-portable（tag v*-windows）
```

网页版：https://shidaifufen.github.io/todoocard-web/

---

## 八、注意事项

- 本仓库**不包含**任何 token；推送用临时 remote，推完复原
- `card.html` 由 `server.py` 启动时读取，改完要重启服务 + 硬刷新
- 安卓 debug 包每次 CI 重新签名，覆盖安装会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先 `adb uninstall`
- 手机已 root（KernelSU），HCI 日志在 `/data/log/bt/`，不是 `/data/misc/bluetooth/logs/`
- **不要执行 `adb reboot`**（曾误操作导致用户设备重启）
