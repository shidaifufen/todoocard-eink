# Android 固定发布签名

从 1.3.0 开始，正式构建必须使用同一份 PKCS12 签名，后续增加 versionCode 后即可覆盖升级。

GitHub Repository Secrets：

- `ANDROID_KEYSTORE_BASE64`：固定 `.p12` 文件的 Base64。
- `ANDROID_KEYSTORE_PASSWORD`：密钥库及私钥共用口令。

Gradle 从 `ANDROID_KEYSTORE_PATH` 和 `ANDROID_KEYSTORE_PASSWORD` 读取配置，别名为 `todocard`。CI 构建后使用 `apksigner verify --print-certs` 校验以下 SHA-256 指纹；缺失密钥或指纹不匹配时停止，不发布临时调试签名。

```
946F9707C7EB40B6F5A7BDC75E1F49C0B9FF97F846A15EBC15CC21B2C1AE34BA
```

调试 APK 在提供固定签名环境变量时也使用同一证书。仓库产物默认只上传正式签名的 release APK，不分发随机 debug 签名包。

维护时必须备份密钥和口令，不可为“修复构建”重新生成密钥。首次生成的本机备份位于用户 `.codex/private/todocard-eink/signing/`，不在 Git 仓库里。

1.2.0 及更早版本的工作流没有保存临时 debug 私钥，因此不能仅凭已发布 APK 还原私钥。如果手机里的旧版证书与固定证书不同，需要最后一次卸载旧版后安装 1.3.0。此后的正式发布保持该证书与应用 ID 不变，并递增 versionCode。
