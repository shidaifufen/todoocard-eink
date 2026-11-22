# 土豆片 · 安卓版（原生 Kotlin）

TodooCard / T3 六色墨水屏的**原生安卓客户端**：Compose UI、原生 BLE、前台服务 + WorkManager 后台推送、系统照片选择器读相册。

> 状态：**v0.1 骨架**。已实现「选照片 → 六色打包 → BLE 直推」与协议常量化；模板渲染、定时策略、相册批量仍待补齐。

## 兼容性

| 项 | 说明 |
|---|---|
| minSdk | 26（Android 8.0） |
| targetSdk | 35（Android 15）；Android 16/17 可直接运行（向前兼容） |
| 鸿蒙 | **HarmonyOS 4 及更早**（兼容 AOSP 分支）可安装 APK；**HarmonyOS NEXT 不再支持 APK**，需单独做 ArkTS 应用 |
| 屏幕 | 任意尺寸，UI 自适应 |

> 关于“Android 17”：目前 Google 最新公开版本是 Android 16（API 36）。本工程用 `compileSdk 35 / targetSdk 35`，在 Android 16/17 上按系统的向前兼容策略运行；等 API 37 SDK 发布后只需改一行。

## 构建

没有本地 Android SDK 也能出包——用 GitHub Actions：

1. 仓库 → Actions → `build-apk` → Run workflow
2. 下载产物 `todocard-debug-apk`（debug 签名，可直接安装）

本地构建（需 JDK 17 + Android SDK 35）：

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 已实现

- **六色打包**：`Payload.kt` 是桌面版 `payload.py` 的 Kotlin 移植
  - cover 裁切 528×792、Floyd–Steinberg 抖动、4bit/像素
  - 面板标定（旋转 180° + 水平翻转）、控制器视角（顺时针 + 镜像）
  - 老固件兼容包（每 64 字节加 `74 43 40` 头）
- **BLE 协议**：`BleClient.kt` 实现服务/特征发现、通知订阅、命令 01/03、数据块 `[idx LE32]+chunk`、回执 `05 00 <idx>` / `05 08`
- **相册**：系统照片选择器（Photo Picker），Android 13+ 无需存储权限
- **后台**：`PushService`（前台服务 + 常驻通知）+ `PushWorker`（WorkManager 周期任务）

## 待办（按优先级）

1. 模板渲染（14 个模板的 Compose Canvas 版，或内嵌渲染器）
2. 定时策略：工作日/周末、间隔推送、免打扰时段
3. 相册批量与收藏、轮播牌堆
4. 图片压缩包（QuickLZ level-3）——legacy 包 218 KB 传输慢，压缩后约 21 KB
5. 首次连接的系统配对引导
