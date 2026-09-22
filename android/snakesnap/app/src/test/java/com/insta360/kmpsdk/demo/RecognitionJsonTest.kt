package com.insta360.kmpsdk.demo

import com.insta360.kmpsdk.demo.recognition.Candidate
import com.insta360.kmpsdk.demo.recognition.QualityIssue
import com.insta360.kmpsdk.demo.recognition.RecognitionError
import com.insta360.kmpsdk.demo.recognition.RecognitionErrorCode
import com.insta360.kmpsdk.demo.recognition.RecognitionJson
import com.insta360.kmpsdk.demo.recognition.RecognitionParseException
import com.insta360.kmpsdk.demo.recognition.RecognitionSource
import com.insta360.kmpsdk.demo.recognition.RecognitionStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

class RecognitionJsonTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private val requestId = "json-test-request"
    private val simulatedSecret = "sk-test-only-upstream-message-do-not-display"

    @Test
    fun singleCandidateFixtureMatchesContract() {
        val response = parse(fixture())
        assertEquals(RecognitionStatus.CANDIDATES, response.status)
        assertEquals(RecognitionSource.MOCK, response.resultSource)
        assertEquals(
            Candidate("pantherophis_guttatus", "玉米蛇", "Pantherophis guttatus", null, null),
            response.candidates.single(),
        )
        assertNull(response.recognitionId)
    }

    @Test
    fun multipleFixtureIsUncertainAndKeepsCandidates() {
        val response = parse(fixture("multiple.json"))
        assertEquals(RecognitionStatus.UNCERTAIN, response.status)
        assertEquals(2, response.candidates.size)
    }

    @Test
    fun pendingFixtureRequiresRecognitionId() {
        val response = parse(fixture("pending.json"))
        assertEquals(RecognitionStatus.PENDING, response.status)
        assertTrue(response.candidates.isEmpty())
        assertEquals("0".repeat(64), response.recognitionId)
        assertRejected(fixture("pending.json").apply { remove("recognitionId") })
        assertRejected(fixture("pending.json").put("recognitionId", "invalid"))
    }

    @Test
    fun noSnakeAndUncertainFixturesMatchContract() {
        assertEquals(RecognitionStatus.NO_SNAKE, parse(fixture("no_snake.json")).status)
        val uncertain = parse(fixture("uncertain.json"))
        assertEquals(RecognitionStatus.UNCERTAIN, uncertain.status)
        assertEquals(listOf(QualityIssue.BLURRED, QualityIssue.TOO_SMALL), uncertain.qualityIssues)
    }

    @Test
    fun errorFixturesMatchContract() {
        assertError(RecognitionErrorCode.INVALID_MODEL_OUTPUT,
            RecognitionJson.parseError(502, fixture("invalid_output.json").toString(), requestId))
        assertError(RecognitionErrorCode.UPSTREAM_TIMEOUT,
            RecognitionJson.parseError(504, fixture("timeout.json").toString(), requestId))
    }

    @Test
    fun missingRequiredFieldsAreRejected() {
        listOf("schemaVersion", "requestId", "status", "candidates", "scoreType", "qualityIssues",
            "latencyMs", "resultSource").forEach { field ->
            assertRejected(fixture().apply { remove(field) }, "missing $field")
        }
        listOf("speciesId", "commonName", "scientificName", "score", "providerScore").forEach { field ->
            assertRejected(fixture().apply { getJSONArray("candidates").getJSONObject(0).remove(field) },
                "missing candidate $field")
        }
    }

    @Test
    fun unknownStatusScoreTypeSourceAndQualityIssueAreRejected() {
        mapOf("status" to "unknown", "schemaVersion" to "2", "scoreType" to "model_self_reported",
            "resultSource" to "unknown").forEach { (key, value) ->
            assertRejected(fixture().put(key, value), "unknown $key")
        }
        assertRejected(fixture().put("qualityIssues", JSONArray().put("unknown")))
    }

    @Test
    fun statusCandidateCountRulesAreEnforced() {
        (1..3).forEach { assertEquals(it, parse(withCandidates(it)).candidates.size) }
        assertRejected(withCandidates(0))
        assertRejected(withCandidates(4))
        assertRejected(fixture().put("status", "no_snake"))
        assertRejected(fixture().put("status", "pending").put("recognitionId", "0".repeat(64)))
        (0..3).forEach { count ->
            assertEquals(count, parse(withCandidates(count).put("status", "uncertain")).candidates.size)
        }
    }

    @Test
    fun scoreMustRemainNullAndProviderScoreIsUncalibratedFiniteNumber() {
        assertRejected(fixture().apply { getJSONArray("candidates").getJSONObject(0).put("score", 0.9) })
        listOf(-12.5, 0.0, 97.6).forEach { value ->
            val response = parse(fixture().apply {
                getJSONArray("candidates").getJSONObject(0).put("providerScore", value)
            })
            assertEquals(value, response.candidates.single().providerScore!!, 0.0)
        }
        assertRejected(fixture().apply {
            getJSONArray("candidates").getJSONObject(0).put("providerScore", "97.6")
        })
    }

    @Test
    fun latencyMustBeANonNegativeInteger() {
        listOf(-1, 0.5, "1", JSONObject.NULL).forEach { value ->
            assertRejected(fixture().put("latencyMs", value))
        }
        assertEquals(0L, parse(fixture().put("latencyMs", 0)).latencyMs)
    }

    @Test
    fun strictJsonRejectsTrailingDataAndSingleQuotes() {
        listOf(fixture().toString() + " trailing", "{'schemaVersion':'1'}").forEach { body ->
            try {
                RecognitionJson.parseSuccess(body, requestId)
                fail("Accepted non-strict JSON")
            } catch (_: RecognitionParseException) {
            }
        }
    }

    @Test
    fun mismatchedRequestIdsAreRejectedButPreParseUnknownIsAcceptedForErrors() {
        assertRejected(fixture().put("requestId", "another-request"))
        val mismatch = fixture("timeout.json").put("requestId", "another-request")
        assertError(RecognitionErrorCode.INVALID_MODEL_OUTPUT,
            RecognitionJson.parseError(504, mismatch.toString(), requestId))
        val unknown = fixture("timeout.json").put("requestId", "unknown").apply {
            getJSONObject("error").put("code", "UNAUTHORIZED")
        }
        assertError(RecognitionErrorCode.UNAUTHORIZED,
            RecognitionJson.parseError(401, unknown.toString(), requestId))
    }

    @Test
    fun allContractErrorCodesRemainSafe() {
        listOf(
            RecognitionErrorCode.INVALID_REQUEST,
            RecognitionErrorCode.INVALID_IMAGE,
            RecognitionErrorCode.IMAGE_TOO_LARGE,
            RecognitionErrorCode.UNAUTHORIZED,
            RecognitionErrorCode.UPLOAD_CONSENT_REQUIRED,
            RecognitionErrorCode.UNKNOWN_RECOGNITION,
            RecognitionErrorCode.OPERATION_IN_PROGRESS,
            RecognitionErrorCode.LOCAL_BUDGET_EXHAUSTED,
            RecognitionErrorCode.UPSTREAM_LIMITED,
            RecognitionErrorCode.UPSTREAM_ERROR,
            RecognitionErrorCode.UPSTREAM_AUTH_ERROR,
            RecognitionErrorCode.UPSTREAM_TIMEOUT,
            RecognitionErrorCode.INVALID_MODEL_OUTPUT,
        ).forEach { code ->
            val body = fixture("timeout.json").apply {
                getJSONObject("error").put("code", code.name).put("message", simulatedSecret)
            }
            assertError(code, RecognitionJson.parseError(500, body.toString(), requestId))
        }
    }

    @Test
    fun malformedErrorBodiesUseHttpFallback() {
        val bodies = listOf(null, "", "{", "[]", "{}", simulatedSecret,
            JSONObject().put("requestId", requestId).put("error", "invalid").toString())
        mapOf(
            400 to RecognitionErrorCode.INVALID_REQUEST,
            401 to RecognitionErrorCode.UNAUTHORIZED,
            403 to RecognitionErrorCode.UPLOAD_CONSENT_REQUIRED,
            404 to RecognitionErrorCode.UNKNOWN_RECOGNITION,
            409 to RecognitionErrorCode.OPERATION_IN_PROGRESS,
            413 to RecognitionErrorCode.IMAGE_TOO_LARGE,
            429 to RecognitionErrorCode.UPSTREAM_LIMITED,
            502 to RecognitionErrorCode.UPSTREAM_ERROR,
            504 to RecognitionErrorCode.UPSTREAM_TIMEOUT,
            500 to RecognitionErrorCode.UNKNOWN,
        ).forEach { (status, code) ->
            bodies.forEach { body -> assertError(code, RecognitionJson.parseError(status, body, requestId)) }
        }
    }

    private fun fixture(name: String = "candidates.json") = recognitionFixture(name, requestId)

    private fun parse(body: JSONObject) = RecognitionJson.parseSuccess(body.toString(), requestId)

    private fun withCandidates(count: Int): JSONObject {
        val root = fixture("multiple.json").put("status", "candidates")
        val source = root.getJSONArray("candidates")
        return root.put("candidates", JSONArray().apply {
            repeat(count) { put(source.getJSONObject(it % source.length())) }
        })
    }

    private fun assertRejected(body: JSONObject, case: String = "invalid response") {
        try {
            parse(body)
            fail("Accepted $case")
        } catch (_: RecognitionParseException) {
        }
    }

    private fun assertError(code: RecognitionErrorCode, error: RecognitionError) {
        assertEquals(code, error.code)
        assertEquals(requestId, error.requestId)
        assertTrue("Error must use the safe display message", error.message == code.displayMessage)
        assertFalse("Upstream message must not be exposed", error.toString().contains(simulatedSecret))
    }
}

internal fun recognitionFixture(name: String, requestId: String): JSONObject {
    val directory = requireNotNull(System.getProperty("recognitionFixtures")) {
        "recognitionFixtures must point to the mock assets directory"
    }
    return JSONObject(File(directory, name).readText(Charsets.UTF_8)).put("requestId", requestId)
}
