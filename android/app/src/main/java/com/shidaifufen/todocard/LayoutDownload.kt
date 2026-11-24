package com.shidaifufen.todocard

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** Public GitHub layout files only; uses no account credentials. */
internal object LayoutDownload {
    const val DEFAULT_URL = "https://github.com/shidaifufen/todoocard-eink/blob/main/layouts/default.json"
    private val hosts = setOf("github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "github-releases.githubusercontent.com")

    fun resolve(input: String): URI {
        val uri = URI(input.trim())
        require(uri.scheme == "https" && uri.host?.lowercase() in setOf("github.com", "raw.githubusercontent.com") &&
            uri.userInfo == null && uri.port in setOf(-1, 443)) { "请输入公开的 GitHub HTTPS 文件链接" }
        val path = uri.rawPath.orEmpty()
        if (uri.host.equals("raw.githubusercontent.com", true)) {
            require(path.split('/').size >= 5) { "请使用 JSON 文件链接" }
            return URI("https://raw.githubusercontent.com$path")
        }
        val parts = path.split('/')
        require(parts.size >= 5 && parts[1].isNotBlank() && parts[2].isNotBlank()) { "请使用 GitHub 文件或 Release 附件链接" }
        return when (parts[3]) {
            "blob", "raw" -> {
                require(parts.size >= 6) { "链接中缺少文件路径" }
                URI("https://raw.githubusercontent.com/${parts[1]}/${parts[2]}/${parts.drop(4).joinToString("/")}")
            }
            "releases" -> {
                require((parts.getOrNull(4) == "download" && parts.size >= 7) ||
                    (parts.getOrNull(4) == "latest" && parts.getOrNull(5) == "download" && parts.size >= 7)) {
                    "请复制 Release 中 JSON 附件的下载链接"
                }
                URI("https://github.com$path")
            }
            else -> error("请使用 GitHub 文件或 Release 附件链接")
        }
    }

    internal fun read(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= LayoutPack.MAX_BYTES) { "版式包不能超过 256 KB" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    fun fetch(input: String): LayoutPack.Pack {
        var uri = resolve(input)
        repeat(6) {
            require(uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.userInfo == null &&
                uri.port in setOf(-1, 443)) { "GitHub 下载重定向地址不受支持" }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json, text/plain;q=0.9")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("User-Agent", "Todocard-Android-Layouts")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    uri = uri.resolve(connection.getHeaderField("Location") ?: error("下载地址缺少重定向目标"))
                } else {
                    check(status == 200) { when (status) {
                        404 -> "找不到文件，请检查链接；仅支持公开仓库或公开 Release"
                        403, 429 -> "GitHub 暂时限制访问，请稍后再试或下载文件后导入"
                        else -> "下载失败（HTTP $status），可下载文件后导入"
                    } }
                    require(connection.contentLengthLong <= LayoutPack.MAX_BYTES) { "版式包不能超过 256 KB" }
                    val raw = connection.inputStream.use(::read)
                    check(!raw.trimStart().startsWith("<")) { "链接返回了网页，请使用 JSON 文件或 Release 附件链接" }
                    return LayoutPack.parse(raw)
                }
            } finally { connection.disconnect() }
        }
        error("GitHub 下载重定向次数过多")
    }
}
