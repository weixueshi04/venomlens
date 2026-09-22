package com.insta360.kmpsdk.demo.recognition

import android.content.Intent
import android.graphics.BitmapFactory
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
import androidx.lifecycle.lifecycleScope
import com.insta360.kmpsdk.demo.MainActivity
import com.insta360.kmpsdk.demo.databinding.ActivityMockRecognitionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class MockRecognitionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMockRecognitionBinding
    private var activeCall: RecognitionCall? = null
    private var activeRequestId: String? = null
    private var pendingRecognitionId: String? = null
    private var image: RecognitionImage? = null
    private val selectImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            cancelRequest()
            image = null
            binding.imagePreview.setImageDrawable(null)
            binding.imagePreview.isVisible = false
            binding.mockResult.text = "已更换照片，旧结果已清除。模拟结果与照片无关。"
            setPreparing(true)
            lifecycleScope.launch {
                try {
                    val prepared = withContext(Dispatchers.IO) { RecognitionImage.read(contentResolver, uri) }
                    image = prepared
                    binding.imagePreview.setImageBitmap(BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.size))
                    binding.imagePreview.isVisible = true
                    binding.imageStatus.text = "已准备 JPEG：${prepared.width} × ${prepared.height}，${prepared.jpeg.size / 1024} KiB\n已校正方向、重新编码，不保留原始 EXIF。尚未上传。"
                } catch (_: IOException) {
                    binding.imageStatus.text = "无法读取图片，请重新选择。"
                } catch (_: SecurityException) {
                    binding.imageStatus.text = "无法访问该图片，请重新选择。"
                } catch (_: IllegalArgumentException) {
                    binding.imageStatus.text = "图片无效或超出 2 MB 限制，请重新选择。"
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
        binding.selectImage.setOnClickListener {
            if (activeRequestId != null) binding.mockResult.text = "已取消模拟请求。"
            cancelRequest()
            selectImage.launch("image/*")
        }
        binding.cancelRequest.setOnClickListener {
            cancelRequest()
            binding.mockResult.text = "已取消模拟请求。"
        }
        binding.manualRefresh.setOnClickListener { refreshPending() }
        MockScenario.entries.forEach { scenario ->
            binding.mockScenarioHost.addView(Button(this).apply {
                text = scenario.label
                setOnClickListener { runScenario(scenario) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun runScenario(scenario: MockScenario) {
        cancelRequest()
        val requestId = UUID.randomUUID().toString()
        activeRequestId = requestId
        setRequestRunning(true)
        binding.mockResult.text = "模拟请求中：${scenario.label}…"
        activeCall = MockRecognitionAdapter(assets, scenario).recognize(requestId, image?.jpeg ?: byteArrayOf()) {
            result -> deliverResult(requestId, result)
        }
    }

    private fun refreshPending() {
        val recognitionId = pendingRecognitionId ?: return
        val requestId = UUID.randomUUID().toString()
        activeRequestId = requestId
        binding.manualRefresh.isVisible = false
        setRequestRunning(true)
        binding.mockResult.text = "正在执行一次手动查询…"
        activeCall = MockRecognitionAdapter(assets, MockScenario.CANDIDATES)
            .refresh(requestId, recognitionId) { result -> deliverResult(requestId, result) }
    }

    private fun deliverResult(requestId: String, result: RecognitionResult) {
        runOnUiThread {
            if (isDestroyed || activeRequestId != requestId) return@runOnUiThread
            activeRequestId = null
            activeCall = null
            setRequestRunning(false)
            binding.mockResult.text = when (result) {
                is RecognitionResult.Success -> {
                    pendingRecognitionId = result.response.recognitionId
                        .takeIf { result.response.status == RecognitionStatus.PENDING }
                    binding.manualRefresh.isVisible = pendingRecognitionId != null
                    renderSuccess(result.response)
                }
                is RecognitionResult.Failure -> {
                    pendingRecognitionId = null
                    binding.manualRefresh.isVisible = false
                    "【MOCK · 识别失败】\n${result.error.message}\n错误码：${result.error.code}\n请求失败不等于没有蛇，请勿自动重试。"
                }
            }
        }
    }

    private fun cancelRequest() {
        activeRequestId = null
        pendingRecognitionId = null
        activeCall?.cancel()
        activeCall = null
        if (::binding.isInitialized) {
            binding.manualRefresh.isVisible = false
            setRequestRunning(false)
        }
    }

    private fun setRequestRunning(running: Boolean) {
        binding.mockScenarioHost.children.forEach { it.isEnabled = !running }
        binding.cancelRequest.isVisible = running
        binding.requestProgress.isVisible = running
    }

    private fun setPreparing(preparing: Boolean) {
        binding.selectImage.isEnabled = !preparing
        binding.mockScenarioHost.children.forEach { it.isEnabled = !preparing }
        binding.requestProgress.isVisible = preparing
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

    override fun onDestroy() {
        cancelRequest()
        super.onDestroy()
    }
}
