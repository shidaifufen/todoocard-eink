# 土豆片 · 六色墨水屏卡片工坊

面向 **TodooCard / T3**（528×792，六色电子墨水屏）的服务端渲染与推送工具：14 个模板、Pillow 服务端出图、定时推送、Web 控制台、蓝牙直传。

> macOS 与 Windows 都已验证；网页版（纯浏览器，Web Bluetooth）见 [todoocard-web](https://github.com/shidaifufen/todoocard-web)。

---


## 安卓版（原生 Kotlin）

- 安装包 [Android 1.8.10](https://github.com/shidaifufen/todoocard-eink/releases/tag/v1.8.10-android)。使用固定签名，可覆盖升级已采用该签名的版本。安装后可在「设置 → 在线升级」检查并安装后续正式版。
- 源码：`android/`；构建：push 到 main 触发 `.github/workflows/build-android.yml`
- 功能：卡片模板、照片裁切与调色、相册、每日计划与发送历史、配对后等广播推送、Android 系统分享与跨窗口拖放图片
- 荣耀任意门由 MagicOS 推荐服务。土豆片已接入 Android 系统图片分享入口，任意门的厂商级独立服务仍需荣耀官方推荐服务接入。[荣耀任意门说明](https://www.honor.com/cn/support/content/zh-cn15868678/)
- 交接与真机调试：见 [HANDOFF-ANDROID.md](HANDOFF-ANDROID.md)

### 在线场景与看板版式

在卡片列表打开「在线场景」，可按名称搜索或随手抽一张。音乐使用 Apple Music 封面，「大封面＋一句」可选一行歌词，「封面＋歌词」点选起止行后自动选中中间各行。歌词来自 LRCLIB。电影海报铺满卡片，底部显示片名和上映年份。计划中可分别选择随机音乐封面、电影海报、英语短语和成语。

先升级一次应用，之后修改引擎支持的布局无需重新打包。进入 **设置 → 外观 → 看板版式包 → 从 GitHub 导入 / 更新**，粘贴公开的 JSON 文件链接，获取并预览后应用。也可以下载 JSON 后选择“导入版式”。相册、内容、设备、计划及密钥与版式单独保存；手动和定时推送共用导入版式。

Android 1.8.6 起，「设置 → 外观 → 看板版式包」可检查上次使用的 GitHub 链接，预览更新并确认应用，也可预览恢复最近 12 份本机版式记录。「设置 → 外观」管理默认字号、间距和照片信息带，「数据源」管理默认联网方式和 API 密钥，「调色预设」提供自然、鲜明、净色、插画净色、黑白的六色预览与单项恢复。照片和在线场景可分别指定默认预设，卡片编辑页可即时调整。联网解析照片城市时会向 Photon 发送四舍五入到小数点后三位的拍摄坐标，可在「外观」关闭联网解析。

- [默认版式](https://github.com/shidaifufen/todoocard-eink/blob/main/layouts/default.json)
- [蓝色天气](https://github.com/shidaifufen/todoocard-eink/blob/main/layouts/weather-blue.json)
- [每日一句＋天气](https://github.com/shidaifufen/todoocard-eink/blob/main/layouts/quote-weather.json)
- [完整格式、组件和更新规则](layouts/README.md)

Windows、macOS 和纯网页版也可以导入同一份 JSON。在「看板版式包」粘贴所选文件的 GitHub 链接，预览后应用。[六色场景预览与文件](layouts/scenes/README.md)

支持 `github.com/用户/仓库/blob/分支/路径.json`、`raw.githubusercontent.com/用户/仓库/分支/路径.json`，以及 `github.com/用户/仓库/releases/download/标签/文件.json`、`releases/latest/download/文件.json`。后续在 GitHub 更新同一分支和路径的 JSON，应用会保留上次链接，再次获取并应用即可更新；固定标签或提交的链接用于锁定版本。仅支持公开文件，不需要 GitHub 令牌。失败不会替换当前版式，可撤销或恢复内置。

天气两种卡片已完整配置化；其他文字卡片可用通用组件替换或叠加，默认保留原版式兼容组件。新 API、新系统能力或引擎尚不支持的组件仍需升级应用。

## 直接下载就能用

不用装 Python、不用命令行，**下载解压 → 双击 → 浏览器自动打开**。

| 平台 | 下载 |
|---|---|
| macOS (Apple Silicon) | [macOS 1.0.27](https://github.com/shidaifufen/todoocard-eink/releases/tag/v1.0.27-macos) |
| Windows (x64) | [Windows 1.0.27](https://github.com/shidaifufen/todoocard-eink/releases/tag/v1.0.27-windows) |

1. 解压到任意文件夹（路径别带特殊符号）
2. macOS 双击 `start-mac.command`（首次右键 → 打开）；Windows 双击 `土豆片卡片工坊.exe`
3. 浏览器自动打开 `http://127.0.0.1:8199/card.html`

数据都存在程序旁的 `data/`（`card.json`、照片缓存、日志），删目录即卸载。

---

## 能做什么

**14 个模板**

每日总结 / 今日待办 / 天气看板 / 色块天气 / 时间进度 / 充电统计 / 每日一句 / 月历 / 倒计时 / 每日诗词 / 照片轮播 / Bing 壁纸 / AI 生图 / 自定义卡片

- **每日总结**：日期、天气、待办、本月进度、全年点阵、底部引文
- **色块天气**：大温度、AQI 色块、三天预报、日出日落、穿衣参考
- **每日诗词**：诗句 + 自动赏析（可接 DeepSeek，也支持手写）
- **照片轮播**：EXIF 信息带、GPS 离线反查城市、按拍摄日期显示当天天气
- **自定义卡片**：积木拼接（标题/日期/天气/待办/引文/点阵/图片/二维码/文本/分割线）

**渲染**

- Pillow 直出 528×792，只用调色板里的 6 个精确色（黑/白/黄/红/蓝/绿）
- 出图后统一吸附调色板，白底不会抖出彩色噪点
- 文字用真实字重，Floyd–Steinberg 误差 0，抖动后仍是实心

**推送**

- 蓝牙直传，QuickLZ level-3 压缩（约 21 KB/张）
- 失败进重试队列，卡片一广播就自动补发
- 定时计划按时间点触发，支持"今日已发"判断与补跑窗口

**Web 控制台**

- 左侧模板列表 + 中间实时预览 + 右侧参数
- 每张卡片都可单独调参数（城市、赏析、积木…）

---

## 字号与行距（三层）

```
实际字号 = 1.1(基准) × 全局滑杆 × 本模板滑杆
实际行距 = 1.1(基准) × 全局滑杆 × 本模板滑杆
```

| 控制 | 位置 | 范围 | 说明 |
|---|---|---|---|
| 全局字号 | 控制台左下「显示」 | 90%~160% | 所有模板 |
| 全局行距 | 上面那个分组 | **60%**~180% | 影响行内行高、块间距、信息带 |
| 本模板字号/行距 | 每个模板的「本模板设置」底部 | 80%~120% | 只影响当前模板，与全局相乘 |
| 自动排版 | 显示分组开关 | 开/关 | 定时发布时按"尽量排满、不越界"自动定字号 |

自动排版的判定：从 1.5 往下试字号，取第一个满足「上边距 ≥12px、下边距 ≥18px、内容高度 ≥ 页面 55%」的值；全都不满足就退回手动值。

**诗词**另有一个「整首优先」开关：关掉时大字优先、放不下就截断补 `……`；打开则字号自动缩到整首放得下。

---

## 照片卡规则

| 情况 | 行为 |
|---|---|
| 有 EXIF（时间/机型/参数） | 底部压一条白色信息带：日期、机身镜头、焦段光圈快门 ISO、拍摄当天天气 |
| 完全没有任何拍摄信息 | **不留信息带，照片铺满整张卡** |
| 信息带高度 | 跟随字号/行距与行数变化，照片区自动让位 |
| 没有机身/参数信息 | 信息带收成**一行**：日期在左，时间与拍摄当天天气在右 |

信息带的天气是**拍摄当天**的天气，不是今天的：

- 今天 → 实时天气
- 近 7 天 → 历史存档，缺数据时用预报接口的 `past_days`
- 更早 → Open-Meteo 历史存档

轮播规则：发送成功后推进牌堆并解钉（下次换图）；失败保留 pin（重试仍发同一张）；「换一张预览」会消耗当前这张，所以确实会换。

---

## 需要你自己准备的

- **TodooCard 卡片**：开机、有电、已配对
- **蓝牙**：macOS 系统蓝牙打开；Windows 需在系统蓝牙里配对卡片
- **网络**：天气/城市/Bing 壁纸/AI 出图需要联网；离线也能用日历、待办、进度、自定义

---


## 时间轴与收藏（v1.0.14）

**时间轴**（设置 → 每日计划底部）

- 默认以「现在」为中心：前 24 小时 ~ 后 24 小时，白线 + 徽标标出现在
- 轴下每 6 小时一个刻度（日期 + 小时）
- 蓝块 = 未来计划（宽度按历史平均耗时估算）；绿/红细条 = 历史成功/失败
- 悬停：显示缩略图与耗时，可直接「重推 / 收藏」
- 未来照片计划：点击蓝块可指定这次要发哪张，到点优先推送
- 左右按钮可前后翻 48 小时

**收藏**

- 模板页或时间轴浮层「☆ 收藏当前照片」
- 左侧「我的收藏」页：缩略图网格、一键推送、取消
- 轮播方式新增「只推收藏」

**冲突处理**

- 同一分钟的固定计划只执行靠前一条
- 间隔任务自动避开固定计划的分钟
- 「一键清理冲突」删除重复计划与撞分钟的间隔任务

**发送历史**

- 缩略图按内容 md5 去重，点击放大
- 保留条数（默认 100）与已用存储可调

## 常见问题

**发送失败 / 一直 `Timed out scanning`、`Timed out connecting`**

macOS 的 CoreBluetooth 一次只允许一个扫描会话。本程序早期版本会在发送前自己扫一遍，和发送器抢射频，导致连接超时——已在 v1.0.13 修复（发送前不再扫描，改用 15 分钟地址缓存）。若仍失败：

1. 卡片拿到电脑 1 米内（RSSI 优于 -70）
2. 长按卡片背面按钮 5 秒唤醒（休眠时广播功率低）
3. 关掉手机上连着卡片的 App
4. 看 `data/app.log` 里的 `土豆片发送失败 ... 原始输出`，会包含 RSSI 与失败原因

**网页打不开 / 提示地址被占用**

端口 8199 被别的程序占了。启动日志会显示实际端口；也可以先 `pkill -f ha_server.py` 再启动。

**卡片显示"缺字"或彩色噪点**

- 检查 `data/app.log` 是否报字体缺失（需要系统中文字体）
- 彩色噪点：出图已做调色板吸附，若仍出现，把该卡参数发出来

**定时任务没触发**

- 定时只在程序运行时生效（网页版需要页面保持打开）
- 检查「每日计划」里的时间有没有被保存（保存后列表会刷新一次）
- 失败会进重试队列，卡片可连时自动补发

**换了电脑/重装后设备 ID 变了**

macOS 的 BLE UUID 是按机器生成的，换机后要在控制台重新「扫描设备」并保存。

---

## 六色与抖动

调色板只有 6 色，所有内容最终会被量化到：

```
黑 (0,0,0)  白 (255,255,255)  黄 (255,255,0)
红 (255,0,0) 蓝 (0,0,255)     绿 (0,255,0)
```

- 照片用 Floyd–Steinberg 抖动（误差 0），远看是连续调，近看是网点
- 文字/线条走"吸附"而不是抖动，边缘干净
- 深色 HUD 主题会把字号再放大 10%，因为二值化会吃掉一圈抗锯齿

---

## 开发

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python server.py            # 起服务，默认 8199
.venv/bin/python tools/check_cards.py # 版式自检（输出问题清单）
```

目录：

```
card.py           核心：配置、出图调度、发送、定时、天气/诗词/照片
card_render.py    14 个模板的 Pillow 渲染
sender.py         Windows/Linux 的 bleak 发送器
payload.py        载荷构建（4bpp 打包、面板校准）
quicklz_payload.py QuickLZ level-3 打包
server.py         aiohttp 壳：静态页 + REST API
web/              控制台（card.html）、场景广场（scenes.html）
packaging/        PyInstaller 规格与打包脚本
tools/            自检、调试脚本
docs/             静态展示页（GitHub Pages）
```

---

## 发布（Release）约定

**每个 Release 都必须挂一个「解压即用」的程序包**，用户不需要 Python、不需要联网装依赖。

| 平台 | 产物 | 打包方式 |
|---|---|---|
| macOS | `todoocard-eink-<版本>-darwin-<arch>.zip` | `PYTHON=.venv/bin/python bash packaging/build_release.sh <版本>` |
| Windows | `todocard-portable-<版本>-windows.zip` | 打 tag `v<版本>-windows`，由 GitHub Actions 在 `windows-latest` 构建 |

Windows 走 CI（PyInstaller 不能交叉编译），工作流在 `.github/workflows/build-windows.yml`：

```bash
git tag v1.0.14-windows && git push origin v1.0.14-windows
```

约 2 分钟出包并自动挂到同名 Release（产物重命名为 ASCII，避免中文名在 Release 上乱码）。

---

## 许可

个人使用自由；转载/二次分发请保留出处。
