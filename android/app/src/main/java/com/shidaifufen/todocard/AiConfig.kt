package com.shidaifufen.todocard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 可选的用户自有接口。密钥由 Android Keystore 加密，不放入日志、导出或卡片数据。 */
object AiConfig {
    data class Config(val endpoint: String = "https://api.deepseek.com/v1", val model: String = "deepseek-chat",
                      val key: String = "", val imageModel: String = "gpt-image-1")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("todocard_api", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("todocard_api", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences("content_api", Context.MODE_PRIVATE)
        val token = runCatching {
            val bytes = Base64.decode(p.getString("encryptedKey", ""), Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }
        }.getOrDefault("")
        return Config(p.getString("endpoint", "https://api.deepseek.com/v1")!!,
            p.getString("model", "deepseek-chat")!!, token, p.getString("imageModel", "gpt-image-1")!!)
    }
    fun save(ctx: Context, config: Config) {
        require(config.endpoint.startsWith("https://")) { "接口地址需要使用 HTTPS" }
        val encrypted = if (config.key.isBlank()) "" else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key())
            Base64.encodeToString(iv + doFinal(config.key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        ctx.getSharedPreferences("content_api", Context.MODE_PRIVATE).edit()
            .putString("endpoint", config.endpoint.trimEnd('/')).putString("model", config.model)
            .putString("imageModel", config.imageModel).putString("encryptedKey", encrypted).apply()
    }
}
