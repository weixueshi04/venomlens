package com.insta360.kmpsdk.demo

import com.insta360.kmpsdk.demo.recognition.HttpRecognitionAdapter
import com.insta360.kmpsdk.demo.recognition.RecognitionErrorCode
import com.insta360.kmpsdk.demo.recognition.RecognitionResult
import com.insta360.kmpsdk.demo.recognition.RecognitionStatus
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpRecognitionAdapterTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(15)

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private val requestId = "http-test-request"
    private val token = "proxy-test-token"
    private val simulatedSecret = "sk-test-only-upstream-message-do-not-display"
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
        0xff.toByte(), 0xd9.toByte())

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        client.dispatcher.cancelAll()
        try {
            server.shutdown()
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            assertTrue(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun postsConsentTokenRequestIdAndExactJpeg() {
        server.enqueue(MockResponse().setBody(successBody()))
        val result = recognize(uploadConsent = true, authToken = token)
        assertTrue(result is RecognitionResult.Success)
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/recognitions", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        val contentType = request.getHeader("Content-Type") ?: throw AssertionError("Missing content type")
        val boundary = contentType.substringAfter("boundary=").trim('"')
        val parts = request.body.readByteArray().toString(Charsets.ISO_8859_1).split("--$boundary")
        assertEquals(5, parts.size)
        assertTrue(parts[1].contains("name=\"requestId\""))
        assertEquals(requestId, parts[1].substringAfter("\r\n\r\n").removeSuffix("\r\n"))
        assertTrue(parts[2].contains("name=\"uploadConsent\""))
        assertEquals("true", parts[2].substringAfter("\r\n\r\n").removeSuffix("\r\n"))
        assertTrue(parts[3].contains("name=\"image\"; filename=\"capture.jpg\""))
        assertArrayEquals(jpeg, parts[3].substringAfter("\r\n\r\n").removeSuffix("\r\n")
            .toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun http202PendingIsAccepted() {
        server.enqueue(MockResponse().setResponseCode(202).setBody(fixtureBody("pending.json")))
        val result = recognize()
        assertTrue(result is RecognitionResult.Success)
        assertEquals(RecognitionStatus.PENDING, (result as RecognitionResult.Success).response.status)
        assertEquals("0".repeat(64), result.response.recognitionId)
    }

    @Test
    fun statusCodeMustMatchPendingState() {
        server.enqueue(MockResponse().setResponseCode(202).setBody(successBody()))
        assertFailure(RecognitionErrorCode.INVALID_MODEL_OUTPUT, recognize())
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixtureBody("pending.json")))
        assertFailure(RecognitionErrorCode.INVALID_MODEL_OUTPUT, recognize())
    }

    @Test
    fun refreshPostsJsonToRecognitionTask() {
        server.enqueue(MockResponse().setBody(successBody()))
        val recognitionId = "a".repeat(64)
        val result = refresh(recognitionId, authToken = token)
        assertTrue(result is RecognitionResult.Success)
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/recognitions/$recognitionId/refresh", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals(requestId, JSONObject(request.body.readUtf8()).getString("requestId"))
    }

    @Test
    fun invalidRecognitionIdIsRejectedBeforeNetwork() {
        val callsStarted = AtomicInteger()
        val localClient = client.newBuilder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { callsStarted.incrementAndGet() }
        }).build()
        assertFailure(RecognitionErrorCode.INVALID_REQUEST, refresh("invalid", localClient))
        assertEquals(0, callsStarted.get())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mismatchedRequestIdReturnsInvalidModelOutput() {
        listOf(200 to "candidates.json", 202 to "pending.json", 504 to "timeout.json").forEach { (status, name) ->
            server.enqueue(MockResponse().setResponseCode(status)
                .setBody(recognitionFixture(name, "another-request").toString()))
            assertFailure(RecognitionErrorCode.INVALID_MODEL_OUTPUT, recognize())
        }
    }

    @Test
    fun contractErrorsRemainSafe() {
        mapOf(
            401 to RecognitionErrorCode.UNAUTHORIZED,
            403 to RecognitionErrorCode.UPLOAD_CONSENT_REQUIRED,
            409 to RecognitionErrorCode.OPERATION_IN_PROGRESS,
            429 to RecognitionErrorCode.LOCAL_BUDGET_EXHAUSTED,
            504 to RecognitionErrorCode.UPSTREAM_TIMEOUT,
        ).forEach { (status, code) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody(errorBody(code)))
            assertFailure(code, recognize())
        }
    }

    @Test
    fun malformedErrorBodiesUseHttpFallback() {
        mapOf(401 to RecognitionErrorCode.UNAUTHORIZED, 504 to RecognitionErrorCode.UPSTREAM_TIMEOUT)
            .forEach { (status, code) ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("{"))
                assertFailure(code, recognize())
            }
    }

    @Test
    fun redirectsAreNotFollowed() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/redirected") MockResponse().setBody(successBody())
                else MockResponse().setResponseCode(302)
                    .setHeader("Location", server.url("/redirected")).setBody("{}")
        }
        assertFailure(RecognitionErrorCode.UNKNOWN, recognize())
        assertEquals("/v1/recognitions", takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun invalidAndOversizedImagesAreRejectedBeforeNetwork() {
        assertRejectedImage(ByteArray(0), RecognitionErrorCode.INVALID_IMAGE)
        val oversized = ByteArray(2_000_001).apply { jpeg.copyInto(this) }
        assertRejectedImage(oversized, RecognitionErrorCode.IMAGE_TOO_LARGE)
        assertRejectedImage(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), RecognitionErrorCode.INVALID_IMAGE)
    }

    @Test
    fun totalCallTimeoutIncludesWaitingForBody() {
        val headersReceived = CountDownLatch(1)
        val timeoutClient = client.newBuilder().callTimeout(150, TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) { headersReceived.countDown() }
            }).build()
        server.enqueue(MockResponse().setBody(successBody()).setBodyDelay(2, TimeUnit.SECONDS))
        assertFailure(RecognitionErrorCode.TIMEOUT, recognize(timeoutClient, callbackTimeoutMs = 1_000))
        assertTrue(headersReceived.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun cancellationSuppressesCallback() {
        val results: BlockingQueue<RecognitionResult> = LinkedBlockingQueue()
        val idle = observeIdle()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = adapter().recognize(requestId, jpeg, true) { results.add(it) }
        takeRequest()
        call.cancel()
        assertTrue(idle.await(5, TimeUnit.SECONDS))
        assertTrue(results.isEmpty())
    }

    @Test
    fun interruptedResponseBodyReturnsNetworkFailure() {
        server.enqueue(MockResponse().setBody(successBody())
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertFailure(RecognitionErrorCode.NETWORK, recognize())
    }

    @Test
    fun missingConsentRejectsBeforeCreatingAnyHttpCall() {
        val started = AtomicInteger()
        val localClient = client.newBuilder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { started.incrementAndGet() }
        }).build()
        val results: BlockingQueue<RecognitionResult> = LinkedBlockingQueue()
        adapter(localClient).recognize(requestId, jpeg) { results.add(it) }
        assertFailure(RecognitionErrorCode.UPLOAD_CONSENT_REQUIRED, awaitResult(results))
        adapter(localClient, mockScenario = "candidates").recognize(requestId, jpeg, false) { results.add(it) }
        assertFailure(RecognitionErrorCode.UPLOAD_CONSENT_REQUIRED, awaitResult(results))
        assertEquals(0, started.get())
        assertEquals(0, server.requestCount)
        assertTrue(results.isEmpty())
    }

    @Test
    fun mockHeadersCoverAllSevenFixturesWithoutAuthorization() {
        mapOf("candidates" to 200, "multiple" to 200, "uncertain" to 200, "no_snake" to 200,
            "pending" to 202, "timeout" to 504, "invalid_output" to 502).forEach { (name, status) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody(fixtureBody("$name.json")))
            val result = recognize(uploadConsent = true, mockScenario = name)
            assertEquals(status < 400, result is RecognitionResult.Success)
            val request = takeRequest()
            assertEquals(name, request.getHeader("X-Mock-Scenario"))
            assertNull(request.getHeader("Authorization"))
        }
        assertEquals(7, server.requestCount)
    }

    @Test
    fun mockRefreshUsesExplicitScenarioWithoutExtraRequest() {
        server.enqueue(MockResponse().setBody(successBody()))
        assertTrue(refresh("a".repeat(64), mockScenario = "candidates") is RecognitionResult.Success)
        assertEquals("candidates", takeRequest().getHeader("X-Mock-Scenario"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun normalAdapterDoesNotSendMockHeader() {
        server.enqueue(MockResponse().setBody(successBody()))
        recognize()
        assertNull(takeRequest().getHeader("X-Mock-Scenario"))
    }

    @Test
    fun mockAdapterRejectsNonMockSuccessAndErrorSources() {
        listOf("live", "cache").forEach { source ->
            server.enqueue(MockResponse().setBody(JSONObject(successBody()).put("resultSource", source).toString()))
            assertFailure(RecognitionErrorCode.INVALID_MODEL_OUTPUT, recognize(mockScenario = "candidates"))
            server.enqueue(MockResponse().setResponseCode(504)
                .setBody(JSONObject(errorBody(RecognitionErrorCode.UPSTREAM_TIMEOUT)).put("resultSource", source).toString()))
            assertFailure(RecognitionErrorCode.INVALID_MODEL_OUTPUT, recognize(mockScenario = "timeout"))
        }
    }

    @Test
    fun mockConfigurationRejectsUnknownScenariosRemoteHostsAndTokens() {
        assertThrows(IllegalArgumentException::class.java) { adapter(mockScenario = "unsupported") }
        assertThrows(IllegalArgumentException::class.java) { adapter(authToken = token, mockScenario = "candidates") }
        assertThrows(IllegalArgumentException::class.java) {
            HttpRecognitionAdapter("https://example.invalid", client = client, mockScenario = "candidates")
        }
        assertEquals(0, server.requestCount)
    }

    private fun adapter(httpClient: OkHttpClient = client, authToken: String? = null, mockScenario: String? = null) =
        HttpRecognitionAdapter(server.url("/").toString(), authToken, httpClient, mockScenario)

    private fun successBody() = fixtureBody("candidates.json")

    private fun fixtureBody(name: String) = recognitionFixture(name, requestId).toString()

    private fun errorBody(code: RecognitionErrorCode) = recognitionFixture("timeout.json", requestId).apply {
        getJSONObject("error").put("code", code.name).put("message", simulatedSecret)
    }.toString()

    private fun observeIdle() = CountDownLatch(1).also { idle ->
        client.dispatcher.idleCallback = Runnable { idle.countDown() }
    }

    private fun recognize(
        httpClient: OkHttpClient = client,
        callbackTimeoutMs: Long = 5_000,
        uploadConsent: Boolean = true,
        authToken: String? = null,
        mockScenario: String? = null,
    ): RecognitionResult {
        val results: BlockingQueue<RecognitionResult> = LinkedBlockingQueue()
        val idle = observeIdle()
        adapter(httpClient, authToken, mockScenario).recognize(requestId, jpeg, uploadConsent) { results.add(it) }
        val result = awaitResult(results, callbackTimeoutMs)
        assertTrue(idle.await(5, TimeUnit.SECONDS))
        assertTrue(results.isEmpty())
        return result
    }

    private fun refresh(
        recognitionId: String,
        httpClient: OkHttpClient = client,
        authToken: String? = null,
        mockScenario: String? = null,
    ): RecognitionResult {
        val results: BlockingQueue<RecognitionResult> = LinkedBlockingQueue()
        adapter(httpClient, authToken, mockScenario).refresh(requestId, recognitionId) { results.add(it) }
        return awaitResult(results)
    }

    private fun assertRejectedImage(image: ByteArray, code: RecognitionErrorCode) {
        val callsStarted = AtomicInteger()
        val localClient = client.newBuilder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { callsStarted.incrementAndGet() }
        }).build()
        val results: BlockingQueue<RecognitionResult> = LinkedBlockingQueue()
        adapter(localClient).recognize(requestId, image, true) { results.add(it) }
        assertFailure(code, awaitResult(results))
        assertEquals(0, callsStarted.get())
        assertEquals(0, server.requestCount)
        assertTrue(results.isEmpty())
    }

    private fun awaitResult(results: BlockingQueue<RecognitionResult>, timeoutMs: Long = 5_000): RecognitionResult =
        results.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("Recognition callback timed out")

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("HTTP request was not received")

    private fun assertFailure(code: RecognitionErrorCode, result: RecognitionResult) {
        assertTrue("Expected recognition failure", result is RecognitionResult.Failure)
        val error = (result as RecognitionResult.Failure).error
        assertEquals(code, error.code)
        assertEquals(requestId, error.requestId)
        assertTrue(error.message == code.displayMessage)
        assertFalse(error.toString().contains(simulatedSecret))
    }
}
