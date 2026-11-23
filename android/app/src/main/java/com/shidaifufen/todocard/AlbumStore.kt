package com.shidaifufen.todocard

import android.content.Context
import android.net.Uri

/** 系统照片选择器中的单张图片和 SAF 目录使用同一份轮播列表。 */
object AlbumStore {

    private val exts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")

    fun listImages(ctx: Context, albumUris: List<String>): List<Uri> {
        val out = mutableListOf<Uri>()
        albumUris.forEach { s ->
            runCatching {
                val uri = Uri.parse(s)
                if (android.provider.DocumentsContract.isTreeUri(uri)) {
                    val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)
                        ?: return@runCatching
                    walk(root, out, 0)
                } else if (ctx.contentResolver.getType(uri)?.startsWith("image/") == true) {
                    out.add(uri)
                }
            }
        }
        return out.distinct().take(500)
    }

    /** 随机取一张（供计划里的照片轮播使用） */
    fun randomImage(ctx: Context, albumUris: List<String>, except: String? = null): Uri? {
        val all = listImages(ctx, albumUris)
        val candidates = all.filter { it.toString() != except }.ifEmpty { all }
        return candidates.randomOrNull()
    }

    private fun walk(dir: androidx.documentfile.provider.DocumentFile,
                     out: MutableList<Uri>, depth: Int) {
        if (depth > 3 || out.size >= 500) return
        dir.listFiles().forEach { f ->
            if (out.size >= 500) return@forEach
            if (f.isDirectory) walk(f, out, depth + 1)
            else if ((f.name ?: "").substringAfterLast('.', "").lowercase() in exts) out.add(f.uri)
        }
    }
}
