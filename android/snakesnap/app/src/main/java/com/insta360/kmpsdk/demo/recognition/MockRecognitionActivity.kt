package com.insta360.kmpsdk.demo.recognition

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.care.CaseRecordActivity
import com.insta360.kmpsdk.demo.databinding.ActivityMockRecognitionBinding
import com.insta360.kmpsdk.demo.hospital.HospitalDirectoryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.OffsetDateTime
import java.util.UUID

class MockRecognitionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMockRecognitionBinding
    // ADB reverse may leave an idle proxy connection stale; use a fresh connection without retrying.
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
        .build()
    private var activeCall: RecognitionCall? = null
    private var activeRequestId: String? = null
    private var pendingRecognitionId: String? = null
    private var pendingRefreshAdapter: RecognitionAdapter? = null
    private var lastResponse: RecognitionResponse? = null
    private var image: RecognitionImage? = null
    private var originalImageUri: Uri? = null
    private var importedAt: String? = null
    private var preparingImage = false
    private val selectImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            clearRecognition("已更换照片，旧结果已清除。模拟结果与照片无关。")
            image = null
            originalImageUri = uri
            importedAt = OffsetDateTime.now().toString()
            binding.uploadConsent.isChecked = false
            binding.imagePreview.setImageDrawable(null)
            binding.imagePreview.isVisible = false
            setPreparing(true)
            lifecycleScope.launch {
                try {
                    val prepared = withContext(Dispatchers.IO) { RecognitionImage.read(contentResolver, uri) }
                    image = prepared
                    binding.imagePreview.setImageBitmap(BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.size))
                    binding.imagePreview.isVisible = true
                    binding.imageStatus.text = "已准备 JPEG：${prepared.width} × ${prepared.height}，${prepared.jpeg.size / 1024} KiB\n已校正方向、重新编码，不保留原始 EXIF。选图不会自动上传；发送须同意并点击 HTTP 场景。"
                } catch (_: IOException) {
                    binding.imageStatus.text = "无法读取图片，请重新选择；仍可记录伤情。"
                } catch (_: SecurityException) {
                    binding.imageStatus.text = "无法访问该图片，请重新选择；仍可记录伤情。"
                } catch (_: IllegalArgumentException) {
                    binding.imageStatus.text = "图片无效或超出 2 MB 限制，请重新选择；仍可记录伤情。"
                } finally {
                    if (!isDestroyed) setPreparing(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMockRecognitionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.openCamera.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        binding.openHospitals.setOnClickListener { startActivity(Intent(this, HospitalDirectoryActivity::class.java)) }
        binding.recordInjury.setOnClickListener { openCare(true) }
        binding.notBitten.setOnClickListener { openCare(false) }
        binding.latestCase.setOnClickListener { startActivity(CaseRecordActivity.latestIntent(this)) }
        binding.selectImage.setOnClickListener {
            if (activeRequestId != null) binding.mockResult.text = "已取消模拟请求。"
            cancelRequest()
            selectImage.launch("image/*")
        }
        binding.cancelRequest.setOnClickListener {
            clearRecognition("已取消模拟请求。")
        }
        binding.useHttpMock.setOnCheckedChangeListener { _, checked ->
            binding.httpMockOptions.isVisible = checked
            binding.uploadConsent.isChecked = false
            clearRecognition(if (checked) "HTTP MOCK：选图并同意发送后，点击场景。真实识别未启用。" else "本地 MOCK：不发送网络请求。请选择场景。")
        }
        binding.proxyBaseUrl.addTextChangedListener {
            if (binding.useHttpMock.isChecked) {
                binding.uploadConsent.isChecked = false
                clearRecognition("代理地址已更改，旧结果已清除；请重新确认发送。")
            }
        }
        binding.manualRefresh.setOnClickListener { refreshPending() }
        MockScenario.entries.forEach { scenario ->
            binding.mockScenarioHost.addView(Button(this).apply {
                text = scenario.label
                setOnClickListener { runScenario(scenario) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun openCare(bitten: Boolean) {
        val summary = if (activeRequestId != null) {
            "【识别尚未完成】\n${binding.mockResult.text}\n进入伤情记录已取消等待，不代表安全。"
        } else binding.mockResult.text.toString()
        val labels = ArrayList(lastResponse?.candidates.orEmpty().map { "${it.commonName} / ${it.scientificName}" })
        cancelRequest()
        startActivity(CaseRecordActivity.intent(this, originalImageUri, importedAt, summary, labels, bitten))
    }

    private fun runScenario(scenario: MockScenario) {
        clearRecognition("尚未获得本次识别结果。")
        val useHttp = binding.useHttpMock.isChecked
        if (useHttp && image == null) {
            binding.mockResult.text = "HTTP MOCK 需要先选择可用图片；仍可直接记录伤情和求助。"
            return
        }
        if (useHttp && !binding.uploadConsent.isChecked) {
            binding.mockResult.text = "UPLOAD_CONSENT_REQUIRED：未同意发送，未创建图片请求。仍可记录伤情。"
            return
        }
        val adapter: RecognitionAdapter
        val refreshAdapter: RecognitionAdapter
        try {
            adapter = if (useHttp) httpAdapter(scenario) else MockRecognitionAdapter(assets, scenario)
            refreshAdapter = if (useHttp) httpAdapter(MockScenario.CANDIDATES) else MockRecognitionAdapter(assets, MockScenario.CANDIDATES)
        } catch (_: IllegalArgumentException) {
            binding.mockResult.text = "代理地址无效：仅支持本机回环地址，不可包含凭据或查询参数；真实识别未启用。"
            return
        }
        val requestId = UUID.randomUUID().toString()
        activeRequestId = requestId
        pendingRefreshAdapter = refreshAdapter
        setRequestRunning()
        binding.mockResult.text = "${if (useHttp) "HTTP MOCK" else "模拟"}请求中：${scenario.label}…"
        val call = adapter.recognize(requestId, image?.jpeg ?: byteArrayOf(), useHttp && binding.uploadConsent.isChecked) {
            result -> deliverResult(requestId, result)
        }
        if (activeRequestId == requestId) activeCall = call
    }

    private fun httpAdapter(scenario: MockScenario) = HttpRecognitionAdapter(
        binding.proxyBaseUrl.text.toString().trim(),
        client = httpClient,
        mockScenario = scenario.assetFile.removeSuffix(".json"),
    )

    private fun refreshPending() {
        val recognitionId = pendingRecognitionId ?: return
        val adapter = pendingRefreshAdapter ?: return
        val requestId = UUID.randomUUID().toString()
        activeRequestId = requestId
        binding.manualRefresh.isVisible = false
        setRequestRunning()
        binding.mockResult.text = "正在执行一次手动查询…"
        val call = adapter.refresh(requestId, recognitionId) { result -> deliverResult(requestId, result) }
        if (activeRequestId == requestId) activeCall = call
    }

    private fun deliverResult(requestId: String, result: RecognitionResult) {
        runOnUiThread {
            if (isDestroyed || activeRequestId != requestId) return@runOnUiThread
            activeRequestId = null
            activeCall = null
            setRequestRunning()
            binding.mockResult.text = when (result) {
                is RecognitionResult.Success -> {
                    lastResponse = result.response
                    binding.speciesComparisonHost.removeAllViews()
                    result.response.candidates.forEach { candidate ->
                        binding.speciesComparisonHost.addView(Button(this).apply {
                            text = "${candidate.commonName} · 离线比对资料（非诊断）"
                            setOnClickListener {
                                startActivity(com.insta360.kmpsdk.demo.species.SpeciesComparisonActivity.intent(
                                    this@MockRecognitionActivity, candidate.speciesId, result.response.resultSource.name,
                                ))
                            }
                        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    }
                    pendingRecognitionId = result.response.recognitionId
                        .takeIf { result.response.status == RecognitionStatus.PENDING }
                    binding.manualRefresh.isVisible = pendingRecognitionId != null
                    if (pendingRecognitionId == null) pendingRefreshAdapter = null
                    renderSuccess(result.response)
                }
                is RecognitionResult.Failure -> {
                    lastResponse = null
                    pendingRecognitionId = null
                    pendingRefreshAdapter = null
                    binding.manualRefresh.isVisible = false
                    val source = result.error.resultSource?.name ?: "来源未确认"
                    "【$source · 识别失败】\n${result.error.message}\n错误码：${result.error.code}\n请求失败不等于没有蛇；不自动重试，仍可记录伤情和求助。"
                }
            }
        }
    }

    private fun clearRecognition(message: String) {
        cancelRequest()
        lastResponse = null
        binding.speciesComparisonHost.removeAllViews()
        binding.mockResult.text = message
    }

    private fun cancelRequest() {
        activeRequestId = null
        pendingRecognitionId = null
        pendingRefreshAdapter = null
        activeCall?.cancel()
        activeCall = null
        if (::binding.isInitialized) {
            binding.manualRefresh.isVisible = false
            setRequestRunning()
        }
    }

    private fun setRequestRunning() {
        val running = activeRequestId != null
        binding.mockScenarioHost.children.forEach { it.isEnabled = !running && !preparingImage }
        binding.useHttpMock.isEnabled = !running && !preparingImage
        binding.proxyBaseUrl.isEnabled = !running && !preparingImage
        binding.uploadConsent.isEnabled = !running && !preparingImage
        binding.cancelRequest.isVisible = running
        binding.requestProgress.isVisible = running || preparingImage
    }

    private fun setPreparing(preparing: Boolean) {
        preparingImage = preparing
        binding.selectImage.isEnabled = !preparing
        setRequestRunning()
        if (preparing) binding.imageStatus.text = "正在准备 JPEG…"
    }

    private fun renderSuccess(response: RecognitionResponse): String = buildString {
        val source = when (response.resultSource) {
            RecognitionSource.MOCK -> "MOCK · 模拟结果"
            RecognitionSource.LIVE -> "LIVE · 本次供应商响应"
            RecognitionSource.CACHE -> "CACHE · 历史结果回放"
        }
        appendLine("【$source】")
        appendLine(when (response.status) {
            RecognitionStatus.CANDIDATES -> "候选蛇种（不代表已确认）"
            RecognitionStatus.NO_SNAKE -> "未检测到蛇，不代表现场安全。"
            RecognitionStatus.UNCERTAIN -> "无法可靠判断；仍保留可用候选。"
            RecognitionStatus.PENDING -> "识别处理中；仅在人工确认后查询一次。"
        })
        response.candidates.forEachIndexed { index, candidate ->
            appendLine("\n${index + 1}. ${candidate.commonName}\n${candidate.scientificName}")
            candidate.providerScore?.let { appendLine("供应商原始分值：$it（含义未经校准）") }
        }
        appendLine("\n无可靠置信度，仅展示候选排序。")
        if (response.qualityIssues.isNotEmpty()) {
            appendLine("\n画质提醒：" + response.qualityIssues.joinToString("、") {
                when (it) {
                    QualityIssue.BLURRED -> "画面模糊"
                    QualityIssue.TOO_SMALL -> "目标过小"
                    QualityIssue.LOW_LIGHT -> "光线不足"
                }
            })
        }
        response.recognitionId?.let { appendLine("\n识别任务：$it") }
        appendLine("\n响应耗时字段：${response.latencyMs} ms")
        if (response.resultSource == RecognitionSource.CACHE) appendLine("缓存耗时不是本次请求耗时。")
        appendLine("识别结果不能用于排除危险或替代医疗判断。")
    }

    override fun onStop() {
        if (activeRequestId != null) clearRecognition("离开页面，已取消请求；识别未完成不代表安全。")
        super.onStop()
    }

    override fun onDestroy() {
        cancelRequest()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
