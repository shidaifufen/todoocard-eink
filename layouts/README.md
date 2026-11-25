# 土豆片版式包

Android、Windows、macOS 和纯网页版支持同一套 JSON。Android 从「设置 → 外观」进入，桌面工坊从「显示」进入，纯网页版在右侧打开「看板版式包」。选择场景后可按卡面需要调整标题、强调色、编号和信息区块，更新预览后再应用。导出的 JSON 包含已应用的样式修改。

版式和本地内容分别存储。未来修改文字位置、字号、颜色、框线、图表或组合元素时，发布 JSON 版式包即可；用户不用重新安装 APK。

## 使用

1. 将 JSON 文件下载至手机。
2. 应用 → 设置 → 外观 → 看板版式包 → 导入版式。
3. 查看各张卡片预览，点击“应用版式”。卡片预览、手动发送和定时发送同时使用新布局。
4. 可导出当前完整配置、导出内置默认配置、撤销上次更改、恢复内置版式。

包内只列出需要更改的卡片，未列出的保留现状。应用前会校验文件格式、元素边界、数据字段，并尝试渲染包内全部卡片；失败不覆盖现有版式。应用版本升级保留导入内容。

### 六色场景

可一键预览 10 张精选卡面。将文件链接粘贴到看板版式包的 GitHub 导入框，确认后应用。[六色场景精选](https://github.com/shidaifufen/todoocard-eink/blob/main/layouts/featured.json)

新增的 24 套场景各有独立 JSON。可先看日程、天气、阅读和学习等分类预览，再打开所选文件并复制 GitHub 链接导入。[浏览场景和预览](scenes/README.md)

当前文件：

- `default.json`：完整默认版式。与 APK 内 `assets/layouts/default.json` 保持相同。
- `weather-blue.json`：两种天气卡片的配色示例，可直接导入。
- `quote-weather.json`：把句子与天气放到同一张卡片上的示例，会自动识别所需在线内容。
- [晨间简报](daily-brief.json)合并日期、天气、待办、年度进度和引文。
- [出门天气](weather-field.json)展示小时温度趋势、未来三天高低温、湿度和日出日落。
- [天气色卡](weather-tiles.json)用满版色块显示三天预报、AQI、体感和出门参考。
- [专注清单](focus-list.json)为待办保留大字和多行空间。
- [诗词日历](verse-almanac.json)包含诗词日签和年度进度两张卡片。

## 从 GitHub 导入和后续更新

应用 → 设置 → 外观 → 看板版式包 → **从 GitHub 导入 / 更新**。粘贴链接后选择“获取并预览”，检查卡片后应用。入口中也能直接选择本仓库默认版式、蓝色天气和一句＋天气。上次成功获取的链接会保留，再次打开并获取即可检查该文件的最新内容；不会自动覆盖用户当前版式。

支持公开 GitHub 文件的以下格式（仓库主页、文件夹页和 Release 介绍页不是文件链接）：

| 链接类型 | 示例 |
|---|---|
| 仓库文件页面，自动转成原始 JSON | `https://github.com/shidaifufen/todoocard-eink/blob/main/layouts/default.json` |
| Raw 文件 | `https://raw.githubusercontent.com/shidaifufen/todoocard-eink/main/layouts/default.json` |
| 指定 Release 的 JSON 附件 | `https://github.com/shidaifufen/todoocard-eink/releases/download/v1.5.0-android/default.json` |
| 最新 Release 的同名 JSON 附件 | `https://github.com/shidaifufen/todoocard-eink/releases/latest/download/default.json` |

日常更新推荐使用上面的 `main/layouts/…` 链接：维护者只更新仓库同一路径的配置，无需构建 APK；用户重新获取、预览、应用即可。指定提交或固定 Release 标签的链接会锁定版本，不会跟随 main 更新。`latest` 指整个仓库最新正式 Release，只有该版本含同名 JSON 附件时才可用。

仅支持 HTTPS 公开文件，不读取或要求 GitHub 令牌；Raw 域名出现网络错误时尝试公开 GitHub Contents API（匿名请求有 GitHub 频率限制，备用路径推荐使用 main 等不含斜杠的分支名或提交 SHA）。GitHub 网络不可用时可通过浏览器下载后本地导入。下载在后台执行，限制大小为 256 KB，校验和预览成功前保留现有配置；下载并不等于应用。请勿向配置写入 API 密钥、蓝牙地址、照片或个人内容。

纯网页版优先使用仓库文件页或 Raw 链接。遇到浏览器无法读取的 Release 附件，可下载 JSON 再导入。

## 格式

画布固定为 528×792。位置以左上角为原点，单位为像素；文本使用文字区域的左上角，不使用基线。元素按数组顺序绘制，后面的覆盖前面的，每个元素都会裁剪到自己的范围。

```json
{
  "schemaVersion": 1,
  "name": "我的天气排版",
  "templates": {
    "weather2": {
      "name": "色块天气",
      "sources": ["weather"],
      "nodes": [
        {"type":"rect","x":32,"y":32,"width":464,"height":728,"fill":"white","border":"black"},
        {"type":"text","x":48,"y":48,"width":300,"height":48,"text":"{{weatherCity}} · {{weatherText}}","textSize":28,"bold":true},
        {"type":"text","x":48,"y":130,"width":300,"height":150,"text":"{{weatherTemp}}°","textSize":110,"color":"red"},
        {"type":"weatherIcon","x":370,"y":140,"width":100,"height":100},
        {"type":"forecastBars","x":48,"y":360,"width":432,"height":160},
        {"type":"text","x":48,"y":700,"width":432,"height":35,"text":"更新 {{weatherUpdated}}","textSize":18}
      ]
    }
  }
}
```

顶层只支持 `schemaVersion`、`name`、`templates`；每个模板只支持 `name`、`sources`、`nodes`。

`templates` 的卡片键：`summary`、`quote`、`todo`、`progress`、`calendar`、`weather`、`weather2`、`countdown`、`poem`、`custom`。

天气看板和色块天气的内置布局已完整使用元素配置。其余卡片默认保留原版式，表现为一个 `builtin` 兼容元素；可以缩放、移动它，叠加其他元素，或用新的 `text`、`list`、`block` 等元素替换整个布局。`builtin` 内部的细节仍由原渲染器提供，修改其内部位置时须替换成独立元素。

照片、Bing、AI 图片继续使用照片编辑器，不在此版式包中。新增引擎尚不支持的组件、新 API 接入或系统能力仍需升级应用。

## 元素

所有元素都有 `type`、`x`、`y`、`width`、`height`。可选 `showIf` 为某个内容字段名：该字段为空时不显示。

| type | 作用 | 其他字段 |
|---|---|---|
| rect | 色块、框线、分割线 | fill、border、borderWidth（默认 2）、radius |
| text | 绑定内容或固定文字 | text、textSize、color、bold、align、wrap、shrink、lineHeight |
| list | 自动换行的内容列表 | source、numbered、emptyText、textSize、color、bold、align、lineHeight |
| weatherIcon | 当前天气图标 | 无 |
| weatherHours | 小时温度折线 | color |
| forecastBars | 三天高低温柱状图 | color |
| block | 已有组合元素，按区域等比缩放 | blockType、text、textSize、centered、color |
| builtin | 原生卡片完整布局，按区域缩放 | kind（卡片键） |

`textSize` 默认 24，范围 10～160。`align` 为 left / center / right，默认 left。文字默认自动换行（wrap=true）、缩小适配（shrink=true）；超过区域会裁剪或省略。`lineHeight` 默认 1.4。应用内字号、行距仍参与文字渲染。图表内部保持统一比例，可通过元素宽高与位置调整。

`list.source`：todos / poemLines / customLines / weatherForecast / countdowns。

`blockType`：title / bigdate / weather / todos / quote / poem / dots / countdown / text / qr / rule。图片文件由用户在应用内管理，不从版式包读取路径。

颜色：white / black / red / blue / green / yellow。另有 aqiColor（随 AQI 变化）和 aqiInk（配套文字色）。所有颜色保持六色输出。

每个模板最多 96 个元素，文件最多 256 KB。未知版本、字段、越界位置和不支持的元素会被拒绝，避免拼写错误悄悄失效。配置是数据，不执行代码，也不能改写蓝牙参数。

## 内容绑定

文字中用 `{{字段名}}`。内容始终从用户保存的数据和在线刷新结果读取；版式包不包含用户的照片、设备、计划或 API 密钥。

| 类别 | 字段 |
|---|---|
| 天气 | weatherCity、weatherText、weatherTemp、weatherHigh、weatherLow、weatherAqi、weatherHumidity、weatherFeels、weatherSunrise、weatherSunset、weatherUpdated、wearAdvice |
| 日期时间 | date、time、week、year、month、day、yearPercent |
| 每日一句 | quote、quoteBy |
| 诗词 | poemTitle、poemBy、poemText、poemNote |
| 自定义、待办 | customTitle、customText、todoCount |

`sources` 可显式声明 weather / quote / poem，渲染器还会根据绑定字段、图表和组合组件推断依赖。进入卡片时按缓存时效后台刷新；手动刷新和定时推送沿用应用的数据准备规则。用户选择“手动内容”时不联网替换。导入配置不主动请求网络。

## 给后续开发者

版式修改优先提交 `layouts/*.json`，直接把文件交给用户导入。内置默认改动同步到 `android/app/src/main/assets/layouts/default.json`，供新安装使用。更改独立 JSON 不需要触发 Android 构建；更改内置资产或引擎才需要构建 APK。保持 schemaVersion 1 兼容；添加新能力需要引擎版本或新的 schemaVersion。
