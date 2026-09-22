package com.insta360.kmpsdk.demo.recognition

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException

enum class MockScenario(val assetFile: String, val label: String, val httpCode: Int = 200) {
    CANDIDATES("candidates.json", "有候选（1 个）"),
    MULTIPLE("multiple.json", "不确定但有多个候选"),
    UNCERTAIN("uncertain.json", "无法判断"),
    NO_SNAKE("no_snake.json", "未检测到蛇"),
    PENDING("pending.json", "识别处理中（202）", 202),
    ERROR_TIMEOUT("timeout.json", "上游超时（504）", 504),
    ERROR_INVALID_OUTPUT("invalid_output.json", "模型输出违规（502）", 502),
}

class MockRecognitionAdapter(
    private val assets: AssetManager,
    private val scenario: MockScenario,
    private val simulateLatencyMs: Long = 600,
) : RecognitionAdapter {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun recognize(
        requestId: String,
        imageJpeg: ByteArray,
        uploadConsent: Boolean,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall = complete(requestId, callback)

    override fun refresh(
        requestId: String,
        recognitionId: String,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall = complete(requestId, callback)

    private fun complete(
        requestId: String,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall {
        val action = Runnable {
            val result = try {
                val fixture = assets.open("mock/${scenario.assetFile}").bufferedReader().use { it.readText() }
                val body = JSONObject(fixture).put("requestId", requestId).toString()
                if (scenario.httpCode == 200 || scenario.httpCode == 202) {
                    RecognitionResult.Success(RecognitionJson.parseSuccess(body, requestId))
                } else {
                    RecognitionResult.Failure(RecognitionJson.parseError(scenario.httpCode, body, requestId))
                }
            } catch (_: IOException) {
                RecognitionResult.Failure(RecognitionError(RecognitionErrorCode.UNKNOWN, requestId = requestId))
            } catch (_: org.json.JSONException) {
                RecognitionResult.Failure(
                    RecognitionError(RecognitionErrorCode.INVALID_MODEL_OUTPUT, requestId = requestId)
                )
            } catch (_: RecognitionParseException) {
                RecognitionResult.Failure(
                    RecognitionError(RecognitionErrorCode.INVALID_MODEL_OUTPUT, requestId = requestId)
                )
            }
            callback(result)
        }
        mainHandler.postDelayed(action, simulateLatencyMs)
        return RecognitionCall { mainHandler.removeCallbacks(action) }
    }
}
