package com.insta360.kmpsdk.demo.recognition

sealed interface RecognitionResult {
    data class Success(val response: RecognitionResponse) : RecognitionResult
    data class Failure(val error: RecognitionError) : RecognitionResult
}

fun interface RecognitionCall {
    fun cancel()
}

interface RecognitionAdapter {
    fun recognize(
        requestId: String,
        imageJpeg: ByteArray,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall = recognize(requestId, imageJpeg, false, callback)

    fun recognize(
        requestId: String,
        imageJpeg: ByteArray,
        uploadConsent: Boolean,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall

    fun refresh(
        requestId: String,
        recognitionId: String,
        callback: (RecognitionResult) -> Unit,
    ): RecognitionCall
}
