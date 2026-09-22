package com.insta360.kmpsdk.demo.recognition

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

enum class RecognitionStatus { CANDIDATES, NO_SNAKE, UNCERTAIN, PENDING }
enum class ScoreType { UNAVAILABLE }
enum class QualityIssue { BLURRED, TOO_SMALL, LOW_LIGHT }
enum class RecognitionSource { MOCK, LIVE, CACHE }

enum class RecognitionErrorCode(val displayMessage: String) {
    INVALID_REQUEST("请求参数无效，请检查后重试"),
    INVALID_IMAGE("图片无效，请重新选择"),
    IMAGE_TOO_LARGE("图片超过 2 MB，请压缩后重试"),
    UNAUTHORIZED("识别服务未授权"),
    UPLOAD_CONSENT_REQUIRED("请先确认同意上传图片"),
    UNKNOWN_RECOGNITION("识别任务不存在，请重新发起"),
    OPERATION_IN_PROGRESS("识别任务正在处理中，请勿重复提交"),
    LOCAL_BUDGET_EXHAUSTED("识别调用额度已用尽，请联系项目负责人"),
    UPSTREAM_LIMITED("上游服务限流或额度不足，请稍后确认"),
    UPSTREAM_ERROR("识别服务暂不可用"),
    UPSTREAM_AUTH_ERROR("上游识别服务鉴权失败"),
    UPSTREAM_TIMEOUT("识别服务响应超时，请人工确认后再试"),
    INVALID_MODEL_OUTPUT("识别响应不符合约定，请稍后重试"),
    NETWORK("网络连接失败，请检查网络"),
    TIMEOUT("识别等待已超时，请人工确认后再试"),
    UNKNOWN("识别请求失败，请稍后重试"),
}

data class Candidate(
    val speciesId: String,
    val commonName: String,
    val scientificName: String,
    val score: Double?,
    val providerScore: Double?,
)

data class RecognitionResponse(
    val schemaVersion: String,
    val requestId: String,
    val status: RecognitionStatus,
    val candidates: List<Candidate>,
    val scoreType: ScoreType,
    val qualityIssues: List<QualityIssue>,
    val latencyMs: Long,
    val resultSource: RecognitionSource,
    val recognitionId: String?,
)

data class RecognitionError(
    val code: RecognitionErrorCode,
    val message: String = code.displayMessage,
    val requestId: String,
    val resultSource: RecognitionSource? = null,
)

class RecognitionParseException : Exception("Invalid recognition response")

object RecognitionJson {
    private val recognitionIdPattern = Regex("[a-f0-9]{64}")

    fun parseSuccess(body: String, expectedRequestId: String): RecognitionResponse {
        try {
            val root = parseObject(body)
            require(!root.has("error"))
            val schemaVersion = root.text("schemaVersion")
            require(schemaVersion == "1")
            val requestId = root.text("requestId")
            require(requestId == expectedRequestId)
            val status = when (root.text("status")) {
                "candidates" -> RecognitionStatus.CANDIDATES
                "no_snake" -> RecognitionStatus.NO_SNAKE
                "uncertain" -> RecognitionStatus.UNCERTAIN
                "pending" -> RecognitionStatus.PENDING
                else -> throw RecognitionParseException()
            }
            require(root.text("scoreType") == "unavailable")
            val items = root.getJSONArray("candidates")
            require(items.length() <= 3)
            val candidates = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                require(item.get("score") === JSONObject.NULL)
                val rawProviderScore = item.get("providerScore")
                val providerScore = if (rawProviderScore === JSONObject.NULL) null else {
                    require(rawProviderScore is Number)
                    rawProviderScore.toDouble().also { require(it.isFinite()) }
                }
                Candidate(
                    item.text("speciesId"),
                    item.text("commonName"),
                    item.text("scientificName"),
                    null,
                    providerScore,
                )
            }
            val recognitionId = when {
                !root.has("recognitionId") || root.isNull("recognitionId") -> null
                else -> root.text("recognitionId").also { require(recognitionIdPattern.matches(it)) }
            }
            when (status) {
                RecognitionStatus.CANDIDATES -> require(candidates.isNotEmpty())
                RecognitionStatus.NO_SNAKE -> require(candidates.isEmpty())
                RecognitionStatus.UNCERTAIN -> Unit
                RecognitionStatus.PENDING -> require(candidates.isEmpty() && recognitionId != null)
            }
            val rawIssues = root.getJSONArray("qualityIssues")
            val issues = (0 until rawIssues.length()).map { index ->
                when (rawIssues.get(index)) {
                    "blurred" -> QualityIssue.BLURRED
                    "too_small" -> QualityIssue.TOO_SMALL
                    "low_light" -> QualityIssue.LOW_LIGHT
                    else -> throw RecognitionParseException()
                }
            }
            val latency = root.get("latencyMs")
            require(latency is Int || latency is Long)
            val latencyMs = (latency as Number).toLong()
            require(latencyMs >= 0)
            return RecognitionResponse(
                schemaVersion,
                requestId,
                status,
                candidates,
                ScoreType.UNAVAILABLE,
                issues,
                latencyMs,
                parseSource(root.text("resultSource")),
                recognitionId,
            )
        } catch (_: JSONException) {
            throw RecognitionParseException()
        } catch (_: IllegalArgumentException) {
            throw RecognitionParseException()
        }
    }

    fun parseError(httpCode: Int, body: String?, expectedRequestId: String): RecognitionError {
        val fallback = when (httpCode) {
            400 -> RecognitionErrorCode.INVALID_REQUEST
            401 -> RecognitionErrorCode.UNAUTHORIZED
            403 -> RecognitionErrorCode.UPLOAD_CONSENT_REQUIRED
            404 -> RecognitionErrorCode.UNKNOWN_RECOGNITION
            409 -> RecognitionErrorCode.OPERATION_IN_PROGRESS
            413 -> RecognitionErrorCode.IMAGE_TOO_LARGE
            429 -> RecognitionErrorCode.UPSTREAM_LIMITED
            502 -> RecognitionErrorCode.UPSTREAM_ERROR
            504 -> RecognitionErrorCode.UPSTREAM_TIMEOUT
            else -> RecognitionErrorCode.UNKNOWN
        }
        return try {
            val root = parseObject(body ?: "")
            val responseRequestId = root.text("requestId")
            if (responseRequestId != expectedRequestId && responseRequestId != "unknown") {
                RecognitionError(RecognitionErrorCode.INVALID_MODEL_OUTPUT, requestId = expectedRequestId)
            } else {
                val code = when (root.getJSONObject("error").text("code")) {
                    "INVALID_REQUEST" -> RecognitionErrorCode.INVALID_REQUEST
                    "INVALID_IMAGE" -> RecognitionErrorCode.INVALID_IMAGE
                    "IMAGE_TOO_LARGE" -> RecognitionErrorCode.IMAGE_TOO_LARGE
                    "UNAUTHORIZED" -> RecognitionErrorCode.UNAUTHORIZED
                    "UPLOAD_CONSENT_REQUIRED" -> RecognitionErrorCode.UPLOAD_CONSENT_REQUIRED
                    "UNKNOWN_RECOGNITION" -> RecognitionErrorCode.UNKNOWN_RECOGNITION
                    "OPERATION_IN_PROGRESS" -> RecognitionErrorCode.OPERATION_IN_PROGRESS
                    "LOCAL_BUDGET_EXHAUSTED" -> RecognitionErrorCode.LOCAL_BUDGET_EXHAUSTED
                    "UPSTREAM_LIMITED" -> RecognitionErrorCode.UPSTREAM_LIMITED
                    "UPSTREAM_ERROR" -> RecognitionErrorCode.UPSTREAM_ERROR
                    "UPSTREAM_AUTH_ERROR" -> RecognitionErrorCode.UPSTREAM_AUTH_ERROR
                    "UPSTREAM_TIMEOUT" -> RecognitionErrorCode.UPSTREAM_TIMEOUT
                    "INVALID_MODEL_OUTPUT" -> RecognitionErrorCode.INVALID_MODEL_OUTPUT
                    else -> fallback
                }
                val source = if (root.has("resultSource") && !root.isNull("resultSource")) {
                    parseSource(root.text("resultSource"))
                } else null
                RecognitionError(code, requestId = expectedRequestId, resultSource = source)
            }
        } catch (_: JSONException) {
            RecognitionError(fallback, requestId = expectedRequestId)
        } catch (_: IllegalArgumentException) {
            RecognitionError(fallback, requestId = expectedRequestId)
        } catch (_: RecognitionParseException) {
            RecognitionError(fallback, requestId = expectedRequestId)
        }
    }

    private fun parseObject(body: String): JSONObject {
        val tokener = JSONTokener(body)
        val root = tokener.nextValue() as? JSONObject ?: throw RecognitionParseException()
        if (tokener.nextClean() != '\u0000') throw RecognitionParseException()
        return root
    }

    private fun parseSource(value: String): RecognitionSource = when (value) {
        "mock" -> RecognitionSource.MOCK
        "live" -> RecognitionSource.LIVE
        "cache" -> RecognitionSource.CACHE
        else -> throw RecognitionParseException()
    }

    private fun JSONObject.text(key: String): String =
        (get(key) as? String)?.takeIf { it.isNotBlank() } ?: throw RecognitionParseException()
}
