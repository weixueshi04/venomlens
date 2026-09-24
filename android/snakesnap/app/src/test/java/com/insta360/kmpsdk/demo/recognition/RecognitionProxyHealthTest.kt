package com.insta360.kmpsdk.demo.recognition

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 探测器是「有网就必须真实识别」这条口径的守门人：
 * 它判 true 我们才走真实代理，判 false 我们才会落到本地数据集并把原因写在界面上。
 * 所以「探测失败」与「探测成功」都必须有测试盯着，不能只测 happy path。
 */
class RecognitionProxyHealthTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun anyHttpResponseCountsAsReachable() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        assertTrue(RecognitionProxyHealth.reachable(server.url("/").toString(), "token"))
        // 打的是 /healthz，且带上 token（端点上不校验，但换成需要鉴权的探针时不必再改调用方）
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/healthz"))
        assertTrue(recorded.getHeader("Authorization") == "Bearer token")
    }

    @Test
    fun serverErrorIsStillReachable() {
        // 可达性是「HTTP 有响应」，不是「业务成功」：5xx 说明代理在，只是自己有问题，
        // 这种情况不该被误判成「没网」而悄悄换成本地数据集。
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(RecognitionProxyHealth.reachable(server.url("/").toString(), null))
    }

    @Test
    fun refusedAndBlankConfigsAreUnreachable() {
        val port = server.port
        server.shutdown()
        assertFalse(RecognitionProxyHealth.reachable("http://127.0.0.1:$port", null))
        assertFalse(RecognitionProxyHealth.reachable(null, "token"))
        assertFalse(RecognitionProxyHealth.reachable("   ", "token"))
        assertFalse(RecognitionProxyHealth.reachable("not a url", "token"))
    }

    @Test
    fun trailingSlashInBaseUrlDoesNotDoubleUp() {
        server.enqueue(MockResponse().setResponseCode(200))
        val base = server.url("/").toString() // 形如 http://localhost:PORT/
        assertTrue(RecognitionProxyHealth.reachable(base, null))
        assertTrue(server.takeRequest().path == "/healthz")
    }
}
