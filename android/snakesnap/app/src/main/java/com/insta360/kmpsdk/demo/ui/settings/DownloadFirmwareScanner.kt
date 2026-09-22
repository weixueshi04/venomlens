package com.insta360.kmpsdk.demo.ui.settings

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * 通过 [MediaStore.Downloads] 枚举「下载」集合中已入库的 .bin / .pkg（无需直接文件路径）。
 * Android 13+ 若系统未向本应用暴露对应行可能为空，此时应使用 SAF（OpenDocument）。
 */
data class DownloadFirmwareEntry(val contentUri: Uri, val displayName: String)

fun queryDownloadFirmwarePackages(context: Context): List<DownloadFirmwareEntry> {
    val resolver = context.contentResolver
    val collection =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection =
        arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
        )
    val selection =
        "(" +
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?" +
            ")"
    val args = arrayOf("%.bin", "%.pkg")
    val sort = "${MediaStore.Downloads.DATE_MODIFIED} DESC"
    val out = mutableListOf<DownloadFirmwareEntry>()
    return try {
        resolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndex(MediaStore.Downloads._ID)
            val nameCol = c.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
            if (idCol < 0 || nameCol < 0) return out
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                if (!name.endsWith(".bin", ignoreCase = true) &&
                    !name.endsWith(".pkg", ignoreCase = true)
                ) {
                    continue
                }
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                out.add(DownloadFirmwareEntry(uri, name))
            }
        }
        out
    } catch (_: SecurityException) {
        emptyList()
    }
}
