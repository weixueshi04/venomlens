package com.insta360.kmpsdk.demo.recognition

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HttpRecognitionAdapter(
    baseUrl: String,
    private val authToken: String? = null,
    client: OkHttpClient = OkHttpClient(),
) : RecognitionAdapter {
    private val endpoint = (baseUrl.trimEnd('/') + "/v1/recognitions").toHttpUrl()
    private val okClient = client.newBuilder()
        .callTimeout(minOf(client.callTimeoutMillis.takeIf { it > 0 } ?: 25_000, 25_000).toLong(), TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    init {
        val loopback = endpoint.host in setOf("localhost", "127.0.0.1", "::1")
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty())
        require(endpoint.query == null && endpoint.fragment == null)
        require(endpoint.isHttps || loopback)
        require(authToken.isNullOrBlank() || endpoint.isHttps || loopback)
    }

    override fun recognize(
        requestId: String,
        imageJpeg: ByteArray,
        uploadConsent: Boolean,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall {
        val requestError = validateRequestId(requestId)
        if (requestError != null) return failLocally(requestError, requestId, callback)
        val imageError = when {
            imageJpeg.size > MAX_RECOGNITION_IMAGE_BYTES -> RecognitionErrorCode.IMAGE_TOO_LARGE
            imageJpeg.size < 3 || imageJpeg[0] != 0xff.toByte() ||
                imageJpeg[1] != 0xd8.toByte() || imageJpeg[2] != 0xff.toByte() -> RecognitionErrorCode.INVALID_IMAGE
            else -> null
        }
        if (imageError != null) return failLocally(imageError, requestId, callback)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("requestId", requestId)
            .addFormDataPart("uploadConsent", uploadConsent.toString())
            .addFormDataPart("image", "capture.jpg", imageJpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        return execute(Request.Builder().url(endpoint).post(multipart).authorized().build(), requestId, callback)
    }

    override fun refresh(
        requestId: String,
        recognitionId: String,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall {
        val requestError = validateRequestId(requestId)
        if (requestError != null) return failLocally(requestError, requestId, callback)
        if (!Regex("[a-f0-9]{64}").matches(recognitionId)) {
            return failLocally(RecognitionErrorCode.INVALID_REQUEST, requestId, callback)
        }
        val url = endpoint.newBuilder().addPathSegment(recognitionId).addPathSegment("refresh").build()
        val body = JSONObject().put("requestId", requestId).toString()
            .toRequestBody("application/json".toMediaType())
        return execute(Request.Builder().url(url).post(body).authorized().build(), requestId, callback)
    }

    private fun execute(
        request: Request,
        requestId: String,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall {
        val call = okClient.newCall(request)
        val cancelled = AtomicBoolean(false)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cancelled.get()) callback(networkFailure(e, requestId))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val source = it.body?.source() ?: throw RecognitionParseException()
                        if (source.request(1024L * 1024 + 1)) throw RecognitionParseException()
                        val body = source.readUtf8()
                        if (it.code == 200 || it.code == 202) {
                            val parsed = RecognitionJson.parseSuccess(body, requestId)
                            val validStatus = if (it.code == 202) {
                                parsed.status == RecognitionStatus.PENDING
                            } else {
                                parsed.status != RecognitionStatus.PENDING
                            }
                            if (!validStatus) throw RecognitionParseException()
                            RecognitionResult.Success(parsed)
                        } else {
                            RecognitionResult.Failure(RecognitionJson.parseError(it.code, body, requestId))
                        }
                    }
                } catch (error: IOException) {
                    networkFailure(error, requestId)
                } catch (_: RecognitionParseException) {
                    RecognitionResult.Failure(
                        RecognitionError(RecognitionErrorCode.INVALID_MODEL_OUTPUT, requestId = requestId)
                    )
                }
                if (!cancelled.get()) callback(result)
            }
        })
        return RecognitionCall {
            cancelled.set(true)
            call.cancel()
        }
    }

    private fun Request.Builder.authorized(): Request.Builder = apply {
        authToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    private fun validateRequestId(requestId: String): RecognitionErrorCode? =
        if (Regex("[A-Za-z0-9._-]{1,64}").matches(requestId)) null else RecognitionErrorCode.INVALID_REQUEST

    private fun failLocally(
        code: RecognitionErrorCode,
        requestId: String,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall {
        callback(RecognitionResult.Failure(RecognitionError(code, requestId = requestId)))
        return RecognitionCall {}
    }

    private fun networkFailure(error: IOException, requestId: String): RecognitionResult.Failure =
        RecognitionResult.Failure(
            RecognitionError(
                if (error is InterruptedIOException) RecognitionErrorCode.TIMEOUT else RecognitionErrorCode.NETWORK,
                requestId = requestId,
            )
        )
}
