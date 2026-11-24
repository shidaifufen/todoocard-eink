package com.shidaifufen.todocard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** 系统相册目录按 MediaStore bucket 授权，避免把系统相册误当作 SAF 文件夹。 */
object MediaAlbums {
    data class Album(val id: String, val title: String, val count: Int, val cover: Uri)
    private val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    fun canRead(ctx: Context) = permissions().any {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
    fun limited(ctx: Context) = Build.VERSION.SDK_INT >= 34 &&
        ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED

    fun list(ctx: Context): List<Album> {
        if (!canRead(ctx)) return emptyList()
        val albums = linkedMapOf<String, Album>()
        ctx.contentResolver.query(images, arrayOf(MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(1) ?: continue
                val current = albums[id]
                albums[id] = if (current == null) Album(id, cursor.getString(2) ?: "未命名相册", 1,
                    ContentUris.withAppendedId(images, cursor.getLong(0)))
                else current.copy(count = current.count + 1)
            }
        }
        return albums.values.toList()
    }
    fun photos(ctx: Context, bucket: String): List<Uri> {
        if (!canRead(ctx)) return emptyList()
        val result = mutableListOf<Uri>()
        ctx.contentResolver.query(images, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(bucket),
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext() && result.size < 5000)
                result.add(ContentUris.withAppendedId(images, cursor.getLong(0)))
        }
        return result
    }
    fun source(id: String) = "todocard-album://bucket/$id"
}
