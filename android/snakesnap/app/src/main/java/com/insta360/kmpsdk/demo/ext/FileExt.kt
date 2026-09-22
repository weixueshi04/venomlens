package com.insta360.kmpsdk.demo.ext

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream


// 判断是否是 APP 私有目录
private fun isInAppPrivateDir(context: Context, targetPath: String): Boolean {
    val file = File(targetPath)
    val appDataDir = File(context.applicationInfo.dataDir)
    val extFilesDir = context.getExternalFilesDir(null)
    val extCacheDir = context.externalCacheDir
    val mediaDirs = context.externalMediaDirs

    val privateRoots = mutableListOf<File>().apply {
        add(appDataDir)
        extFilesDir?.let { add(it) }
        extCacheDir?.let { add(it) }
        mediaDirs?.forEach { add(it) }
    }

    return privateRoots.any {
        file.absolutePath.startsWith(it.absolutePath)
    }
}

suspend fun copyFile(context: Context, sourceFile: File, targetPath: String, isDeleteSource: Boolean = false): Boolean = withContext(Dispatchers.IO) {
    if (!sourceFile.exists()) return@withContext false
    val isSuccess = if (isInAppPrivateDir(context, targetPath)) {
        copyByFile(sourceFile, File(targetPath))
    } else {
        copyByMediaStore(context, sourceFile, targetPath)
    }
    if (isSuccess && isDeleteSource) sourceFile.delete()

    return@withContext isSuccess
}

private fun copyByFile(source: File, target: File): Boolean {
    return try {
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}

private fun copyByMediaStore(context: Context, source: File, targetPath: String): Boolean {
    val resolver = context.contentResolver
    val targetFile = File(targetPath)
    val fileName = targetFile.name
    val mimeType = getMimeType(fileName)

    val contentValues = android.content.ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        val relativePath = getRelativePath(targetPath)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
    }

    val uri = when {
        mimeType.startsWith("image") -> {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        }

        mimeType.startsWith("video") -> {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        }

        else -> {
            resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
        }
    } ?: return false

    return try {
        resolver.openOutputStream(uri)?.use { output ->
            source.source().buffer().use { source ->
                output.sink().buffer().use { sink ->
                    source.readAll(sink)
                }
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}


// 自动识别 MIME 类型
private fun getMimeType(fileName: String): String {
    return when {
        fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
        fileName.endsWith(".png", true) -> "image/png"
        fileName.endsWith(".mp4", true) -> "video/mp4"
        else -> "application/octet-stream"
    }
}

// 自动识别保存目录（Pictures/DCIM/Movies）
private fun getRelativePath(path: String): String {
    return when {
        path.contains("/Pictures/") -> "Pictures"
        path.contains("/DCIM/") -> "DCIM"
        path.contains("/Movies/") -> "Movies"
        else -> "Download"
    }
}