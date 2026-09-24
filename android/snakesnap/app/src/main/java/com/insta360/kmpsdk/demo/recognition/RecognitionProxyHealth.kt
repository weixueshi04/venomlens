package com.insta360.kmpsdk.demo.recognition

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 识别代理的可用性探测。
 *
 * 存在的理由：产品口径要求「有网就必须走真实识别，不允许拿本地数据集顶替」，
 * 但「有没有网」不能靠 [com.insta360.kmpsdk.demo.ui.emergency.recognitionModeOf] 这种构建期判断来回答——
 * 那只说明**配置齐不齐**，不说明**代理此刻在不在**（`adb reverse` 一失效，配置照样是齐的）。
 * 所以在真正发起识别之前，先短超时打一次代理的 `/healthz`。
 *
 * 只认「HTTP 有响应」为可达，**不校验状态码**：4xx/5xx 只说明代理自己有问题，
 * 那种情况应当让真实请求去失败并如实报错，而不是悄悄换成本地数据集。
 * 该端点在推理服务里不校验 Authorization（见 inference/app.py 的 `/healthz`），
 * 但仍带上 token，方便日后换成需要鉴权的探针时不必再改调用方。
 */
object RecognitionProxyHealth {

    /** 探测超时。够本机 adb reverse 与局域网生效，又不至于把紧急流程卡住。 */
    private const val PROBE_TIMEOUT_MS = 1_500L

    /**
     * 超时都是常量，所以共用一个 client 就够。不每次新建：OkHttp 的 client 各带一套
     * 连接池与调度线程，识别前每次都造一个会在紧急流程里白白堆线程。
     * 也不复用识别适配器那个零空闲连接池的 client——探测要的是快，不是「每次新建连接」。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 同步探测，调用方负责放到 IO 线程。
     * 任何异常（DNS、连接被拒、超时、URL 解析失败）一律视为不可达，不外抛。
     */
    fun reachable(
        baseUrl: String?,
        authToken: String?,
    ): Boolean {
        if (baseUrl.isNullOrBlank()) return false
        val endpoint = (baseUrl.trimEnd('/') + "/healthz").toHttpUrlOrNull() ?: return false
        val builder = Request.Builder().url(endpoint).get()
        authToken?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return runCatching {
            // 拿到**任何** HTTP 响应都算「代理在」，包括 4xx / 5xx。
            // 5xx 说明代理自己有毛病，那种情况必须让真实请求去失败并如实报错，
            // 而不是悄悄换成本地数据集糊过去——后者正是「用本地数据集冒充 AI 识图」的来源。
            // 只有连响应都拿不到（连接被拒 / 超时 / DNS 解析失败）才判不可达。
            client.newCall(builder.build()).execute().use { }
            true
        }.getOrDefault(false)
    }
}
