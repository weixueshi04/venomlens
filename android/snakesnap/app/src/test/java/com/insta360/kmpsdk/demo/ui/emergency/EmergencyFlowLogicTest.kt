package com.insta360.kmpsdk.demo.ui.emergency

import com.insta360.kmpsdk.demo.recognition.Candidate
import com.insta360.kmpsdk.demo.recognition.QualityIssue
import com.insta360.kmpsdk.demo.recognition.RecognitionResponse
import com.insta360.kmpsdk.demo.recognition.RecognitionSource
import com.insta360.kmpsdk.demo.recognition.RecognitionStatus
import com.insta360.kmpsdk.demo.recognition.ScoreType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * 紧急一键流程纯逻辑测试：文件名匹配、求助出口可用性红线、合规文案。
 * 只覆盖不依赖 Android / SDK 的部分（见 EmergencyFlowLogic.kt）。
 */
class EmergencyFlowLogicTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    // ── basename ────────────────────────────────────────────────────────────

    @Test
    fun basenameHandlesUnixAndWindowsSeparators() {
        assertEquals("VID_20260923.jpg", EmergencyFlowFiles.basename("/mnt/sd/DCIM/VID_20260923.jpg"))
        assertEquals("VID_20260923.jpg", EmergencyFlowFiles.basename("http://192.168.42.1/DCIM/VID_20260923.jpg"))
        assertEquals("VID_20260923.jpg", EmergencyFlowFiles.basename("\\storage\\emulated\\0\\VID_20260923.jpg"))
        assertEquals("VID_20260923.jpg", EmergencyFlowFiles.basename("VID_20260923.jpg"))
    }

    @Test
    fun basenameTrimsTrailingSeparatorsAndWhitespace() {
        assertEquals("a.jpg", EmergencyFlowFiles.basename("/dcim/a.jpg/"))
        assertEquals("a.jpg", EmergencyFlowFiles.basename("  /dcim/a.jpg\\  "))
    }

    // ── 相机路径 → WorkWrapper 匹配 ─────────────────────────────────────────

    @Test
    fun matchesCameraWorkByBasenameAcrossDifferentPrefixes() {
        // onCaptureFinish 给相机侧路径，getAllCameraWorks 给 http URL，前缀必然不同，只能比文件名。
        val wanted = listOf("/mnt/sd/DCIM/IMG_0001.jpg")
        val candidates = listOf(
            "http://192.168.42.1/DCIM/IMG_0000.jpg",
            "http://192.168.42.1/DCIM/IMG_0001.jpg",
        )
        assertEquals(1, EmergencyFlowFiles.indexOfFirstMatch(wanted, candidates))
        assertEquals(candidates[1], EmergencyFlowFiles.firstMatchByBasename(wanted, candidates))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(
            0,
            EmergencyFlowFiles.indexOfFirstMatch(
                listOf("/dcim/img_0007.jpg"),
                listOf("http://cam/DCIM/IMG_0007.JPG"),
            ),
        )
    }

    @Test
    fun matchingAcceptsAnyOfMultipleFilePaths() {
        // 连拍/HDR 会一次回调多个路径，任一命中即可。
        val wanted = listOf("/dcim/A.jpg", "/dcim/B.jpg", "/dcim/C.jpg")
        val candidates = listOf("http://cam/X.jpg", "http://cam/B.jpg", "http://cam/A.jpg")
        // 返回的是候选里第一个命中的（B 在 A 之前）。
        assertEquals("http://cam/B.jpg", EmergencyFlowFiles.firstMatchByBasename(wanted, candidates))
    }

    @Test
    fun noMatchReturnsNullAndMinusOne() {
        val wanted = listOf("/dcim/IMG_0001.jpg")
        val candidates = listOf("http://cam/IMG_9999.jpg", "http://cam/IMG_0002.mp4")
        assertNull(EmergencyFlowFiles.firstMatchByBasename(wanted, candidates))
        assertEquals(-1, EmergencyFlowFiles.indexOfFirstMatch(wanted, candidates))
    }

    @Test
    fun emptyInputsNeverMatch() {
        assertEquals(-1, EmergencyFlowFiles.indexOfFirstMatch(emptyList(), listOf("http://cam/A.jpg")))
        assertEquals(-1, EmergencyFlowFiles.indexOfFirstMatch(listOf("/dcim/A.jpg"), emptyList()))
        // 纯分隔符的路径 basename 为空，不能把两个空名当成相等而误配。
        assertEquals(-1, EmergencyFlowFiles.indexOfFirstMatch(listOf("///"), listOf("/")))
    }

    // ── 链路入口判定 ────────────────────────────────────────────────────────

    @Test
    fun onlyPhotosEnterThePipeline() {
        assertTrue(EmergencyFlowPolicy.shouldEnterPipeline(isPhoto = true))
        assertFalse(EmergencyFlowPolicy.shouldEnterPipeline(isPhoto = false))
    }

    // ── 红线：失败不得阻断求助路径 ──────────────────────────────────────────

    @Test
    fun helpExitsStayEnabledInEveryFailureCombination() {
        for (pipelineFailed in listOf(true, false)) {
            for (imageUnavailable in listOf(true, false)) {
                for (recognitionFailed in listOf(true, false)) {
                    for (hasPhoto in listOf(true, false)) {
                        assertTrue(
                            "求助出口必须恒可用: pipelineFailed=$pipelineFailed " +
                                "imageUnavailable=$imageUnavailable recognitionFailed=$recognitionFailed hasPhoto=$hasPhoto",
                            EmergencyFlowPolicy.helpExitsEnabled(
                                pipelineFailed,
                                imageUnavailable,
                                recognitionFailed,
                                hasPhoto,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun mockRunsEvenWithoutAnImage() {
        // MOCK 结果与照片字节无关，读图失败仍应给出候选展示。
        assertTrue(EmergencyFlowPolicy.runMockWithoutImage())
    }

    // ── 候选标签 ────────────────────────────────────────────────────────────

    @Test
    fun candidateLabelsUseCommonAndScientificName() {
        assertEquals(
            listOf("玉米蛇 / Pantherophis guttatus"),
            EmergencyFlowPolicy.candidateLabels(listOf("玉米蛇" to "Pantherophis guttatus")),
        )
    }

    @Test
    fun candidateLabelsCapAtThreeToMatchCaseRecord() {
        val four = (1..4).map { "俗名$it" to "Sci$it" }
        val labels = EmergencyFlowPolicy.candidateLabels(four)
        assertEquals(3, labels.size)
        assertEquals("俗名1 / Sci1", labels[0])
        assertEquals("俗名3 / Sci3", labels[2])
    }

    @Test
    fun candidateLabelsEmptyWhenNoCandidates() {
        assertTrue(EmergencyFlowPolicy.candidateLabels(emptyList()).isEmpty())
    }

    // ── 合规文案 ────────────────────────────────────────────────────────────

    @Test
    fun summaryKeepsEveryCompliancePhrase() {
        val response = response(
            status = RecognitionStatus.CANDIDATES,
            candidates = listOf(
                Candidate("pantherophis_guttatus", "玉米蛇", "Pantherophis guttatus", null, 0.87),
            ),
            qualityIssues = listOf(QualityIssue.BLURRED),
        )
        val text = renderRecognitionSummary(response)

        // MOCK 标注必须在文案里。
        assertTrue(text.contains("MOCK · 模拟结果"))
        // 候选不得表述为已确认 / 准确率。
        assertTrue(text.contains("候选蛇种（不代表已确认）"))
        assertTrue(text.contains("供应商原始分值：0.87（含义未经校准）"))
        assertTrue(text.contains("无可靠置信度，仅展示候选排序。"))
        assertTrue(text.contains("画面模糊"))
        assertTrue(text.contains("识别结果不能用于排除危险或替代医疗判断。"))
        // 红线：不得出现「准确率」「确诊」「诊断结果」这类表述。
        assertFalse(text.contains("准确率"))
        assertFalse(text.contains("确诊"))
    }

    @Test
    fun summaryForNoSnakeStillWarnsItIsNotProofOfSafety() {
        val text = renderRecognitionSummary(
            response(RecognitionStatus.NO_SNAKE, emptyList(), emptyList()),
        )
        assertTrue(text.contains("未检测到蛇，不代表现场安全。"))
        assertTrue(text.contains("识别结果不能用于排除危险或替代医疗判断。"))
    }

    @Test
    fun summaryMarksUncertainAndPendingWithoutConfidenceClaims() {
        assertTrue(
            renderRecognitionSummary(response(RecognitionStatus.UNCERTAIN, emptyList(), emptyList()))
                .contains("无法可靠判断；仍保留可用候选。"),
        )
        val pending = renderRecognitionSummary(
            response(RecognitionStatus.PENDING, emptyList(), emptyList(), recognitionId = "0".repeat(64)),
        )
        assertTrue(pending.contains("识别处理中；仅在人工确认后查询一次。"))
        assertTrue(pending.contains("识别任务：${"0".repeat(64)}"))
    }

    @Test
    fun summaryNotesCacheLatencyIsNotRequestLatency() {
        val text = renderRecognitionSummary(
            response(
                RecognitionStatus.CANDIDATES,
                listOf(Candidate("s1", "俗名", "Sci", null, null)),
                emptyList(),
                source = RecognitionSource.CACHE,
            ),
        )
        assertTrue(text.contains("CACHE · 历史结果回放"))
        assertTrue(text.contains("缓存耗时不是本次请求耗时。"))
    }

    private fun response(
        status: RecognitionStatus,
        candidates: List<Candidate>,
        qualityIssues: List<QualityIssue>,
        source: RecognitionSource = RecognitionSource.MOCK,
        recognitionId: String? = null,
    ) = RecognitionResponse(
        schemaVersion = "1",
        requestId = "emergency-test",
        status = status,
        candidates = candidates,
        scoreType = ScoreType.UNAVAILABLE,
        qualityIssues = qualityIssues,
        latencyMs = 120,
        resultSource = source,
        recognitionId = recognitionId,
    )
}
