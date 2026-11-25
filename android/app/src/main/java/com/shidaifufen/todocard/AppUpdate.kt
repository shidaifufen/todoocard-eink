package com.shidaifufen.todocard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal object AppUpdate {
    private const val repository = "shidaifufen/todoocard-eink"
    private const val releases = "https://github.com/$repository/releases/download/"
    private const val catalog = "https://api.github.com/repos/$repository/releases?per_page=50"
    private const val fallback = "https://shidaifufen.github.io/todoocard-eink/android-update.json"
    private const val maxApk = 100L * 1024 * 1024
    private val tag = Regex("""v(\d+)\.(\d+)\.(\d+)-android""")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com", "shidaifufen.github.io")

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
        override fun toString() = "$major.$minor.$patch"
    }
    data class Release(val version: Version, val url: String, val digest: String, val size: Long)

    fun version(value: String): Version? {
        val parts = tag.matchEntire("v$value-android")?.groupValues ?: return null
        return Version(parts[1].toIntOrNull() ?: return null,
            parts[2].toIntOrNull() ?: return null, parts[3].toIntOrNull() ?: return null)
    }

    fun latest(entries: JSONArray): Release? = (0 until entries.length()).mapNotNull { pos ->
        val entry = entries.optJSONObject(pos) ?: return@mapNotNull null
        if (entry.optBoolean("draft") || entry.optBoolean("prerelease")) return@mapNotNull null
        val match = tag.matchEntire(entry.optString("tag_name")) ?: return@mapNotNull null
        val name = match.groupValues.drop(1).joinToString(".")
        val version = version(name) ?: return@mapNotNull null
        val assetName = "todocard-android-$name.apk"
        val address = releases + match.value + "/" + assetName
        val assets = entry.optJSONArray("assets") ?: return@mapNotNull null
        (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.firstNotNullOfOrNull { asset ->
            val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            val size = asset.optLong("size")
            if (asset.optString("name") == assetName && asset.optString("browser_download_url") == address &&
                digest != null && sha256.matches(digest) && size in 1..maxApk) {
                Release(version, address, digest, size)
            } else null
        }
    }.maxByOrNull { it.version }

    private fun open(address: String): HttpURLConnection {
        var url = URI(address)
        repeat(6) {
            require(url.scheme == "https" && url.host in hosts && url.userInfo == null &&
                url.port in listOf(-1, 443)) { "更新地址无效" }
            val connection = url.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "TodooCard-Android")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val code = connection.responseCode
                if (code == 200) return connection
                check(code in listOf(301, 302, 303, 307, 308)) { "GitHub 返回 HTTP $code" }
                url = url.resolve(checkNotNull(connection.getHeaderField("Location")))
                connection.disconnect()
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        error("下载重定向次数过多")
    }

    private fun read(address: String): String {
        val connection = open(address)
        return try {
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "更新目录过大" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally { connection.disconnect() }
    }

    suspend fun check(): Release = withContext(Dispatchers.IO) {
        val apiRelease = try {
            latest(JSONArray(read(catalog))) ?: error("未找到 Android 正式版")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        apiRelease ?: checkNotNull(latest(JSONArray().put(JSONObject(read(fallback))))) {
            "暂时无法读取 GitHub 更新目录"
        }
    }

    @Suppress("DEPRECATION")
    suspend fun download(ctx: Context, release: Release, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val folder = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File.createTempFile("todocard-", ".apk", folder)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            val connection = open(release.url)
            try {
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var percent = -1
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= release.size) { "安装包大小与 GitHub 记录不符" }
                            hash.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val next = (total * 100 / release.size).toInt()
                            if (next != percent) {
                                percent = next
                                withContext(Dispatchers.Main) { progress(next) }
                            }
                        }
                        check(total == release.size) { "安装包下载不完整" }
                    }
                }
            } finally { connection.disconnect() }
            val digest = hash.digest().joinToString("") { "%02x".format(it) }
            check(digest == release.digest) { "安装包 SHA-256 校验失败" }
            val pm = ctx.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val current = pm.getPackageInfo(ctx.packageName, flags)
            val archive = checkNotNull(pm.getPackageArchiveInfo(file.path, flags)) { "无法读取安装包" }
            check(archive.packageName == current.packageName && archive.versionName == release.version.toString() &&
                PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(current)) {
                "安装包应用或版本与更新记录不符"
            }
            val installed = if (Build.VERSION.SDK_INT >= 28) current.signingInfo?.apkContentsSigners else current.signatures
            val candidate = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
            check(!installed.isNullOrEmpty() && installed.toSet() == candidate?.toSet()) {
                "安装包签名与当前应用不同"
            }
            folder.listFiles()?.filter { it != file && it.extension == "apk" }?.forEach { it.delete() }
            file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}

@Composable
internal fun AppUpdateRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember(ctx) { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() }
    var candidate by remember { mutableStateOf<AppUpdate.Release?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf("") }
    fun openInstaller(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            ctx.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { message = "无法打开系统安装器，请重试" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ctx.packageManager.canRequestPackageInstalls()) apk?.let(::openInstaller)
        else message = "安装权限尚未允许，安装包已下载"
    }
    fun install(file: File) {
        if (ctx.packageManager.canRequestPackageInstalls()) openInstaller(file)
        else runCatching {
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
        }.onFailure { message = "请在系统设置中允许土豆片安装应用，再返回重试" }
    }

    SectionCard("在线升级") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("当前版本 $installed", modifier = Modifier.weight(1f).padding(top = 12.dp))
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    progress = -1
                    message = "正在查询 GitHub…"
                    try {
                        val next = AppUpdate.check()
                        val old = AppUpdate.version(installed)
                        if (candidate?.digest != next.digest) apk = null
                        candidate = next.takeIf { old == null || it.version > old }
                        message = if (candidate == null) "已是最新版本" else "发现 Android ${next.version}"
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        message = "检查失败：${error.message ?: "网络不可用"}"
                    } finally { busy = false }
                }
            }) { Text("检查更新") }
        }
        if (busy && progress >= 0) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        candidate?.let { release ->
            Button(enabled = !busy, onClick = {
                scope.launch {
                    val saved = apk?.takeIf { it.isFile && it.length() == release.size }
                    if (saved != null) {
                        install(saved)
                        return@launch
                    }
                    busy = true
                    progress = 0
                    message = "正在下载 Android ${release.version}…"
                    try {
                        val file = AppUpdate.download(ctx, release) { progress = it }
                        apk = file
                        message = "安装包已验证，请在系统安装器确认"
                        install(file)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        message = "更新失败：${error.message ?: "请重试"}"
                    } finally { busy = false }
                }
            }) { Text(if (apk == null) "下载并安装" else "打开系统安装器") }
        }
    }
}
