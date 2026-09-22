package com.insta360.kmpsdk.demo.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * 将 [Uri]（如系统文档选择器返回的 content://）复制到应用缓存目录，返回可读本地文件。
 * 供需要 [java.io.File] 路径的 SDK 接口使用。
 */
fun copyUriToCacheFile(context: Context, uri: Uri): File? {
    val cr = context.contentResolver
    val baseName = cr.queryDisplayName(uri) ?: "firmware.bin"
    val safeName = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
    val target = File(context.cacheDir, "fw_pick_$safeName")
    return try {
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: return null
        target
    } catch (_: Exception) {
        if (target.exists()) target.delete()
        null
    }
}

private fun ContentResolver.queryDisplayName(uri: Uri): String? {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0) return c.getString(i)
        }
    }
    return null
}
