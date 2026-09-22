package com.insta360.kmpsdk.demo.util

import android.content.Context
import com.arashivision.sdk.common.file.InstaFileManager
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 通过本机 `logcat` 进程实时读取日志，写入
 * `<externalFilesDir>/insta/log/dumper/yyyy-MM-dd_HH-mm-ss.log`（文件名为切换时刻的时间戳）。
 * 单文件超过 10 MB 时以当前时间戳生成新文件滚动。
 *
 * 可靠性要点：
 * - 目录自行推导，不依赖 SDK 初始化，因此可在 `Application.onCreate` 即开始录制；
 * - Android 12+ 会把 App fork 出的 logcat 当 phantom process 回收，读循环意外结束时
 *   由看门狗按退避策略自动重启，并从上次写入的时间戳续读；
 * - 启动失败或写入失败都不会改动用户开关，开关只由用户在设置页拨动。
 */
object DemoLogcatDumper {

    private const val MAX_FILE_BYTES = 10L * 1024 * 1024
    private const val RETENTION_DAYS = 3L
    private const val DUMPER_DIR_NAME = "dumper"

    /** 退避重启间隔（秒），按次序取用，末位封顶 */
    private val RETRY_DELAYS_SEC = longArrayOf(2, 5, 10, 30)

    /** 单次运行超过该时长视为已稳定，重置退避计数 */
    private const val STABLE_RUN_MILLIS = 60_000L

    private val FILE_NAME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    private val lock = Any()
    private val processRef = AtomicReference<Process?>(null)
    private val workerRef = AtomicReference<Thread?>(null)

    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "DemoLogcatDumper-Watchdog").apply { isDaemon = true }
        }
    }

    // 供外部线程在导出前调用 flush()
    @Volatile private var activeWriter: OutputStreamWriter? = null
    @Volatile private var running = false

    /** 期望处于运行状态；由用户开关与 [autoStart] 决定，看门狗据此判断是否重启 */
    @Volatile private var desired = false

    /** 已推导（或已对齐 SDK）的 dumper 目录 */
    @Volatile private var dumperDir: File? = null

    /** 最后写入行的 threadtime 时间戳（`MM-dd HH:mm:ss.SSS`），重启时用于续读 */
    @Volatile private var lastLineTimestamp: String? = null

    /** 最后写入行的完整内容，用于去掉 `-T` 闭区间导致的重复首行 */
    @Volatile private var lastLine: String? = null

    private var retryIndex = 0
    private var pendingRetry = false

    private val _runningState = MutableStateFlow(false)

    /** 读循环真正在读取时为 true；供设置页展示真实状态 */
    val runningState: StateFlow<Boolean> = _runningState.asStateFlow()

    /**
     * 在 `Application.onCreate` 调用：按用户开关决定是否启动，不依赖 SDK 初始化与运行时权限。
     */
    fun autoStart(context: Context) {
        val app = context.applicationContext
        if (!DemoAppPreferences.readLogcatDumpEnabled(app)) {
            Timber.i("DemoLogcatDumper disabled by preference")
            return
        }
        desired = true
        synchronized(lock) {
            if (running) return
            val err = startLocked(app)
            if (err != null) {
                Timber.w("DemoLogcatDumper autoStart failed: %s, will retry", err)
                scheduleRetryLocked(app)
            }
        }
    }

    /**
     * 确认仍在运行，未运行则立即重启（不等退避周期）。
     * 用于 `Activity.onResume`：后台期间被系统回收 logcat 子进程时，回前台即恢复。
     */
    fun ensureRunning(context: Context) {
        val app = context.applicationContext
        if (!desired) return
        synchronized(lock) {
            if (running || pendingRetry) return
            val err = startLocked(app)
            if (err != null) {
                Timber.w("DemoLogcatDumper ensureRunning failed: %s, will retry", err)
                scheduleRetryLocked(app)
            }
        }
    }

    /**
     * SDK 初始化后校验推导目录与 [InstaFileManager.getLogDir] 是否一致；
     * 不一致（SDK 侧路径规则变化）则改用 SDK 目录并重启。
     */
    fun alignWithSdkLogDir(context: Context) {
        val app = context.applicationContext
        val sdkLogDir =
            try {
                InstaFileManager.getLogDir()
            } catch (e: Exception) {
                Timber.w(e, "alignWithSdkLogDir: SDK log dir unavailable")
                return
            }
        if (sdkLogDir.isBlank()) return
        val sdkDumperDir = File(sdkLogDir, DUMPER_DIR_NAME)
        val current = dumperDir ?: deriveDumperDir(app)
        if (sdkDumperDir.absolutePath == current.absolutePath) return

        Timber.w(
            "DemoLogcatDumper dir mismatch, switching from %s to %s",
            current.absolutePath,
            sdkDumperDir.absolutePath,
        )
        synchronized(lock) {
            dumperDir = sdkDumperDir
            if (!desired) return
            stopLocked()
            lastLineTimestamp = null
            lastLine = null
            val err = startLocked(app)
            if (err != null) scheduleRetryLocked(app)
        }
    }

    /** 用户在设置页打开开关；返回启动失败原因，失败时看门狗仍会继续重试 */
    fun start(context: Context): String? {
        val app = context.applicationContext
        desired = true
        synchronized(lock) {
            if (running) return null
            val err = startLocked(app)
            if (err != null) scheduleRetryLocked(app)
            return err
        }
    }

    /** 用户在设置页关闭开关；停止后看门狗不再重启 */
    fun stop() {
        desired = false
        synchronized(lock) { stopLocked() }
    }

    /** 导出日志前调用，确保 OutputStreamWriter 内部缓冲落盘 */
    fun flush() {
        try {
            activeWriter?.flush()
        } catch (_: Exception) {
        }
    }

    // region ---- 内部实现 ----

    /**
     * SDK 侧 `getDefaultFilePath()` 返回 `getExternalFilesDir(null)`，且本 App 初始化时未覆盖
     * `fileDir`，故此推导与 [InstaFileManager.getLogDir] 一致；差异由 [alignWithSdkLogDir] 兜住。
     */
    private fun deriveDumperDir(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(root, "insta"), "log").let { File(it, DUMPER_DIR_NAME) }
    }

    private fun stopLocked() {
        if (!running && processRef.get() == null) return
        running = false
        _runningState.value = false
        try {
            processRef.getAndSet(null)?.destroy()
        } catch (_: Exception) {
        }
        workerRef.getAndSet(null)?.interrupt()
    }

    private fun startLocked(context: Context): String? {
        val dir = dumperDir ?: deriveDumperDir(context).also { dumperDir = it }
        if (!dir.exists() && !dir.mkdirs()) return "cannot create dumper dir: ${dir.absolutePath}"

        val process =
            try {
                ProcessBuilder(*buildLogcatArgs()).redirectErrorStream(true).start()
            } catch (e: Exception) {
                return e.message ?: e.toString()
            }

        processRef.set(process)
        running = true
        pendingRetry = false
        val thread =
            Thread({ dumpLoop(context, dir, process) }, "DemoLogcatDumper").apply {
                isDaemon = true
            }
        workerRef.set(thread)
        thread.start()
        Timber.i("DemoLogcatDumper started, dir=%s", dir.absolutePath)
        return null
    }

    /** 调用方需持有 [lock] */
    private fun scheduleRetryLocked(context: Context) {
        if (!desired || pendingRetry) return
        val delaySec = RETRY_DELAYS_SEC[retryIndex.coerceAtMost(RETRY_DELAYS_SEC.lastIndex)]
        if (retryIndex < RETRY_DELAYS_SEC.lastIndex) retryIndex++
        pendingRetry = true
        Timber.i("DemoLogcatDumper retry scheduled in %ds", delaySec)
        try {
            scheduler.schedule(
                {
                    synchronized(lock) {
                        pendingRetry = false
                        if (!desired || running) return@synchronized
                        val err = startLocked(context)
                        if (err != null) {
                            Timber.w("DemoLogcatDumper retry failed: %s", err)
                            scheduleRetryLocked(context)
                        }
                    }
                },
                delaySec,
                TimeUnit.SECONDS,
            )
        } catch (e: Exception) {
            pendingRetry = false
            Timber.w(e, "DemoLogcatDumper cannot schedule retry")
        }
    }

    /**
     * 首次启动取 ring-buffer 最近 200 行；重启时用上次写入的时间戳续读，避免重复与乱序。
     */
    private fun buildLogcatArgs(): Array<String> {
        val since = lastLineTimestamp
        return if (since.isNullOrBlank()) {
            arrayOf("logcat", "-v", "threadtime", "-T", "200", "*:V")
        } else {
            arrayOf("logcat", "-v", "threadtime", "-T", since, "*:V")
        }
    }

    // threadtime 行首形如 `08-12 14:03:21.123 `，取前 18 字符作为 -T 续读锚点
    private fun extractTimestamp(line: String): String? {
        if (line.length < 18) return null
        val ts = line.substring(0, 18)
        return if (ts[2] == '-' && ts[5] == ' ' && ts[8] == ':' && ts[14] == '.') ts else null
    }

    private fun dumpLoop(context: Context, dumperDir: File, process: Process) {
        var writer: OutputStreamWriter? = null
        var currentFile: File? = null
        var linesSinceFlush = 0
        val startedAt = System.currentTimeMillis()
        // -T 为闭区间，续读时首行会与上次末行重复，跳过一次
        var skipDuplicate = lastLine != null

        // 关闭旧 writer 并打开新文件；使用 OutputStreamWriter 直包 FileOutputStream，
        // Java 侧缓冲远小于 BufferedWriter 的 8 KB，写入后内容更快对外部读取可见。
        fun switchFile(file: File) {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {
            }
            currentFile = file
            val w = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
            activeWriter = w
            writer = w
            w.write("--- session ${Instant.now()} ---\n")
            linesSinceFlush = 0
        }

        fun newFile(): File {
            var file = File(dumperDir, "${LocalDateTime.now().format(FILE_NAME_FMT)}.log")
            var idx = 1
            while (file.exists()) {
                file = File(dumperDir, "${LocalDateTime.now().format(FILE_NAME_FMT)}_$idx.log")
                idx++
            }
            return file
        }

        try {
            purgeOldLogs(dumperDir)
            switchFile(newFile())
            _runningState.value = true

            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (running && !Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (skipDuplicate) {
                        skipDuplicate = false
                        if (line == lastLine) continue
                    }

                    if ((currentFile?.length() ?: 0L) > MAX_FILE_BYTES) {
                        // 当前文件超过 10 MB，以新时间戳滚动到下一个文件
                        switchFile(newFile())
                        purgeOldLogs(dumperDir)
                    }

                    val w = writer ?: continue
                    w.write(line + "\n")
                    lastLine = line
                    extractTimestamp(line)?.let { lastLineTimestamp = it }
                    if (++linesSinceFlush >= 8) {
                        w.flush()
                        linesSinceFlush = 0
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Timber.w(e, "DemoLogcatDumper read loop ended")
                // 写入失败可能因磁盘紧张，先清理一次过期日志再交由看门狗重启
                runCatching { purgeOldLogs(dumperDir) }
            }
        } finally {
            activeWriter = null
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {
            }
            var restarting = false
            synchronized(lock) {
                // 仅当本线程仍是当前 worker 时才清理状态：切目录场景下新一轮可能已启动，
                // 此时不能把新一轮的 running 覆盖掉，也不该再排重启
                if (workerRef.get() === Thread.currentThread()) {
                    workerRef.set(null)
                    running = false
                    _runningState.value = false
                    if (processRef.get() === process) processRef.set(null)
                    // 单次运行足够久说明是被系统回收而非启动即失败，重置退避
                    if (System.currentTimeMillis() - startedAt >= STABLE_RUN_MILLIS) retryIndex = 0
                    if (desired) {
                        restarting = true
                        scheduleRetryLocked(context)
                    }
                }
            }
            try {
                process.destroy()
            } catch (_: Exception) {
            }
            if (restarting) Timber.w("DemoLogcatDumper stopped unexpectedly, restarting")
        }
    }

    /** 删除 mtime 早于保留期的日志文件 */
    private fun purgeOldLogs(dumperDir: File) {
        val deadline = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val files = dumperDir.listFiles() ?: return
        var deleted = 0
        files.forEach { f ->
            if (f.isFile && f.name.endsWith(".log") && f.lastModified() < deadline) {
                if (f.delete()) deleted++
            }
        }
        if (deleted > 0) Timber.i("DemoLogcatDumper purged %d expired log files", deleted)
    }

    // endregion
}
