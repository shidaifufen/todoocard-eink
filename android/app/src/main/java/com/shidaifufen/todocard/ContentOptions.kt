package com.shidaifufen.todocard

import android.content.Context
import org.json.JSONArray
import java.security.MessageDigest

/** 默认联网；离线手写内容只在用户主动选用时生效。 */
object ContentOptions {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("content_options", 0)
    fun online(ctx: Context, kind: String) = prefs(ctx).getBoolean("online_$kind", true)
    fun setOnline(ctx: Context, kind: String, online: Boolean) {
        prefs(ctx).edit().putBoolean("online_$kind", online).apply()
    }
    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.filterNot { it.isWhitespace() }.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun seen(ctx: Context, kind: String): List<String> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("seen_$kind", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())
    fun wasSeen(ctx: Context, kind: String, text: String) = hash(text) in seen(ctx, kind)
    @Synchronized fun claim(ctx: Context, kind: String, text: String): Boolean {
        if (wasSeen(ctx, kind, text)) return false
        remember(ctx, kind, text)
        return true
    }
    @Synchronized fun remember(ctx: Context, kind: String, text: String) {
        val history = (seen(ctx, kind) + hash(text)).distinct().takeLast(300)
        prefs(ctx).edit().putString("seen_$kind", JSONArray(history).toString()).apply()
    }
}
