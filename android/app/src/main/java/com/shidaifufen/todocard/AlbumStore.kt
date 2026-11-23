package com.shidaifufen.todocard

import android.content.Context
import android.net.Uri

/** 已添加相册（SAF 目录）里的图片枚举 */
object AlbumStore {

    private val exts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")

    fun listImages(ctx: Context, albumUris: List<String>): List<Uri> {
        val out = mutableListOf<Uri>()
        albumUris.forEach { s ->
            runCatching {
                val tree = Uri.parse(s)
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: return@runCatching
                walk(ctx, root, out, 0)
            }
        }
        return out
    }

    /** 随机取一张（供计划里的照片轮播使用） */
    fun randomImage(ctx: Context, albumUris: List<String>): android.net.Uri? {
        val all = listImages(ctx, albumUris)
        return if (all.isEmpty()) null else all.random()
    }

    private fun walk(ctx: Context, dir: androidx.documentfile.provider.DocumentFile,
                     out: MutableList<Uri>, depth: Int) {
        if (depth > 3 || out.size > 500) return
        dir.listFiles().forEach { f ->
            if (f.isDirectory) walk(ctx, f, out, depth + 1)
            else if ((f.name ?: "").substringAfterLast('.', "").lowercase() in exts) out.add(f.uri)
        }
    }
}
