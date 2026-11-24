package com.shidaifufen.todocard

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

/** Retain the rendered pixels, so replay never picks a new photo or refreshes a past card. */
object HistoryCards {
    private fun directory(ctx: Context) = File(ctx.filesDir, "sent-cards").apply { mkdirs() }
    fun file(ctx: Context, name: String): File? {
        if (!name.matches(Regex("[a-f0-9]{64}\\.png"))) return null
        return File(directory(ctx), name).takeIf { it.isFile }
    }
    fun save(ctx: Context, bitmap: Bitmap): String {
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "无法保存发送快照" }
            out.toByteArray()
        }
        val name = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } + ".png"
        val target = File(directory(ctx), name)
        if (!target.isFile) target.writeBytes(bytes)
        return name
    }
    fun prune(ctx: Context, retained: Set<String>) {
        directory(ctx).listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
    }
}
