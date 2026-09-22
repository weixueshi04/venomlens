package com.insta360.kmpsdk.demo.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 将 [sourceDir] 目录下所有**文件**（含子目录）写入 [zipFile]。
 */
fun zipDirectoryContentsToFile(
    sourceDir: File,
    zipFile: File,
) {
    require(sourceDir.isDirectory) { "source is not a directory: $sourceDir" }
    zipFile.parentFile?.mkdirs()
    val base = sourceDir.canonicalFile
    val basePath = base.path + File.separator
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
        base.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val canonical = file.canonicalFile
                val rel =
                    canonical.path.removePrefix(basePath).ifEmpty { canonical.name }
                val entryName = rel.replace(File.separatorChar, '/')
                FileInputStream(canonical).use { input ->
                    zos.putNextEntry(ZipEntry(entryName))
                    input.copyTo(zos)
                    zos.closeEntry()
                }
            }
    }
}
