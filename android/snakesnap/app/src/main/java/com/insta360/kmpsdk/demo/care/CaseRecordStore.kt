package com.insta360.kmpsdk.demo.care

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

class CaseOriginalUnavailable(val userMessage: String) : IOException(userMessage)

class CaseLatestPointerUnavailable(val record: CaseRecord, cause: IOException) :
    IOException("最近记录入口更新失败", cause)

class CaseRecordStore(context: Context) {
    private val resolver = context.contentResolver
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    suspend fun save(
        draft: CaseDraft,
        originalImageUri: Uri?,
        continueWithoutOriginal: Boolean = false,
    ): CaseRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            val normalized = draft.normalized()
            readRecord(normalized.id)?.let {
                writeLatest(it)
                return@withLock it
            }
            val original = originalPath(normalized.id)
            val status: CaseOriginalStatus
            val bytes: Long
            if (originalImageUri == null || continueWithoutOriginal) {
                if (original.exists() && !original.delete()) throw IOException("无法清理未关联原图")
                status = if (originalImageUri == null) CaseOriginalStatus.NOT_PROVIDED
                else CaseOriginalStatus.OMITTED_AFTER_FAILURE
                bytes = 0
            } else {
                bytes = copyOriginal(originalImageUri, original)
                status = CaseOriginalStatus.SAVED
            }
            val record = CaseRecord(normalized, Instant.now().toString(), status, bytes)
            try {
                atomicWrite(recordPath(record.id)) { it.write(record.toJson().toString().toByteArray(Charsets.UTF_8)) }
            } catch (failure: Exception) {
                if (status == CaseOriginalStatus.SAVED) original.delete()
                throw failure
            }
            writeLatest(record)
            record
        }
    }

    suspend fun load(id: String): CaseRecord? = withContext(Dispatchers.IO) {
        mutex.withLock { readRecord(id) }
    }

    suspend fun loadLatest(): CaseRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pointer = File(directory, LATEST_FILE)
            if (!pointer.exists()) return@withLock null
            val json = JSONObject(pointer.readText(Charsets.UTF_8))
            require(json.getInt("version") == 1)
            readRecord(json.getString("id")) ?: throw IOException("最近记录不存在")
        }
    }

    suspend fun originalFile(record: CaseRecord): File? = withContext(Dispatchers.IO) {
        if (record.originalStatus != CaseOriginalStatus.SAVED) return@withContext null
        originalPath(record.id).takeIf {
            it.isFile && it.length() == record.originalBytes && it.length() in 1..MAX_ORIGINAL_BYTES
        }
    }

    private fun readRecord(id: String): CaseRecord? {
        val file = recordPath(id)
        if (!file.exists()) return null
        return CaseRecord.fromJson(JSONObject(file.readText(Charsets.UTF_8))).also {
            require(it.id == id)
            require(it.originalBytes <= MAX_ORIGINAL_BYTES)
        }
    }

    private suspend fun copyOriginal(uri: Uri, target: File): Long {
        try {
            require(uri.scheme == "content" || uri.scheme == "file")
            var count = 0L
            resolver.openInputStream(uri)?.use { input ->
                atomicWrite(target) { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > MAX_ORIGINAL_BYTES) {
                            throw CaseOriginalUnavailable("原图超过 32 MiB，未保存原图。")
                        }
                        output.write(buffer, 0, read)
                    }
                    if (count == 0L) throw CaseOriginalUnavailable("原图为空，未保存原图。")
                }
            } ?: throw CaseOriginalUnavailable("无法打开原图，未保存原图。")
            return count
        } catch (failure: CaseOriginalUnavailable) {
            throw failure
        } catch (_: SecurityException) {
            throw CaseOriginalUnavailable("原图访问授权已失效，未保存原图。")
        } catch (_: IOException) {
            throw CaseOriginalUnavailable("原图读取或私有存储写入失败，未保存原图。")
        } catch (_: IllegalArgumentException) {
            throw CaseOriginalUnavailable("原图地址不可用，未保存原图。")
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("私有记录目录不可用")
    }

    private fun recordPath(id: String): File {
        require(CaseDraft.isValidId(id))
        return File(directory, "$id.json")
    }

    private fun originalPath(id: String): File {
        require(CaseDraft.isValidId(id))
        return File(directory, "$id.original")
    }

    private suspend fun writeLatest(record: CaseRecord) {
        val json = JSONObject().put("version", 1).put("id", record.id)
        try {
            atomicWrite(File(directory, LATEST_FILE)) { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        } catch (failure: IOException) {
            throw CaseLatestPointerUnavailable(record, failure)
        }
    }

    private suspend fun atomicWrite(target: File, write: suspend (FileOutputStream) -> Unit) {
        val temp = File.createTempFile(".${target.name}-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                write(output)
                currentCoroutineContext().ensureActive()
                output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    companion object {
        const val MAX_ORIGINAL_BYTES = 32L * 1024 * 1024
        internal const val DIRECTORY_NAME = "case_records"
        internal const val LATEST_FILE = "latest.json"
        private val mutex = Mutex()
    }
}
