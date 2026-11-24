package com.shidaifufen.todocard

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object AiRequests {
    fun post(config: AiConfig.Config, path: String, body: JSONObject): JSONObject {
        check(config.endpoint.startsWith("https://") && config.key.isNotBlank()) { "请先在设置中配置 HTTPS 接口与 API Key" }
        val connection = URL(config.endpoint.trimEnd('/') + "/" + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.connectTimeout = 15_000; connection.readTimeout = 90_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${config.key}")
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "接口返回 ${connection.responseCode}，请检查模型、余额和权限" }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }
}
