package com.shidaifufen.todocard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ImageSources {
    fun cached(ctx: Context, kind: String): String = ctx.getSharedPreferences("image_sources", 0)
        .getString("${kind}_path", "").orEmpty()
    private fun save(ctx: Context, kind: String, bytes: ByteArray): String {
        val dir = File(ctx.filesDir, "artworks").apply { mkdirs() }
        val file = File(dir, "${kind}_${System.nanoTime()}.jpg")
        file.writeBytes(bytes)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        check(file.length() > 0 && bounds.outWidth > 0 && bounds.outHeight > 0) { "图片响应无效" }
        val old = cached(ctx, kind)
        ctx.getSharedPreferences("image_sources", 0).edit().putString("${kind}_path", file.path).apply()
        if (old != file.path && old.startsWith(dir.path)) File(old).delete()
        return file.path
    }
    private fun download(url: String): ByteArray {
        require(url.startsWith("https://")) { "图片地址必须使用 HTTPS" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000; c.readTimeout = 25_000
        return try {
            check(c.responseCode in 200..299) { "图片服务返回 ${c.responseCode}" }
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    val read = input.read(buffer); if (read < 0) break
                    out.write(buffer, 0, read)
                    check(out.size() <= 24 * 1024 * 1024) { "图片过大，请使用较小尺寸" }
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }
    suspend fun bing(ctx: Context): String = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("image_sources", 0)
        val index = prefs.getInt("bing_index", -1).plus(1) % 8
        val data = JSONObject(String(download("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$index&n=1&mkt=zh-CN"), Charsets.UTF_8))
            .getJSONArray("images").getJSONObject(0)
        val path = save(ctx, "bing", download("https://www.bing.com" + data.getString("url")))
        prefs.edit().putInt("bing_index", index).putString("bing_credit", data.optString("copyright")).apply()
        path
    }
    suspend fun generate(ctx: Context, prompt: String): String = withContext(Dispatchers.IO) {
        check(prompt.isNotBlank()) { "请填写提示词" }
        val config = AiConfig.load(ctx)
        val data = AiRequests.post(config, "images/generations", JSONObject().put("model", config.imageModel)
            .put("prompt", prompt).put("size", "1024x1024").put("n", 1))
            .getJSONArray("data").getJSONObject(0)
        val encoded = data.optString("b64_json")
        val bytes = if (encoded.isNotBlank()) android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            else download(data.getString("url"))
        check(bytes.size < 24 * 1024 * 1024) { "图片过大" }
        save(ctx, "ai", bytes)
    }
}
