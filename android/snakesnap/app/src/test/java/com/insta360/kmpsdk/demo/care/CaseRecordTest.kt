package com.insta360.kmpsdk.demo.care

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseRecordTest {
    @Test
    fun emptyFieldsAndMissingRecognitionStillProduceACard() {
        val record = record(CaseDraft(bitten = true, details = CaseDetails(" ", "", "\n", "", " ", " ", " ", " ", " ")))
        listOf("图片导入时间", "咬伤时间", "部位", "症状", "年龄", "血型", "手填位置",
            "紧急联系人", "联系人电话", "联系人关系").forEach {
            assertTrue(record.cardText().contains("$it：未提供"))
        }
        assertTrue(record.cardText().contains("用户比对选择：未比对"))
        assertTrue(record.cardText().contains("风险未知，须由医师判断"))
        assertEquals(record, CaseRecord.fromJson(JSONObject(record.toJson().toString())))
    }

    @Test
    fun failedPendingAndUncertainSnapshotsArePreservedVerbatim() {
        listOf(
            "【MOCK · 识别失败】\n错误码：UPSTREAM_TIMEOUT\n请求失败不等于没有蛇。",
            "【MOCK】pending · 识别处理中，尚无结果",
            "【CACHE】uncertain · 无法可靠判断",
            "用户拒绝上传；尚未识别",
        ).forEach { snapshot ->
            val result = CaseRecord.fromJson(record(CaseDraft(bitten = true, recognitionSummary = snapshot)).toJson())
            assertEquals(snapshot, result.draft.recognitionSummary)
            assertTrue(result.cardText().contains(snapshot))
            assertTrue(result.cardText().contains("非诊断"))
        }
    }

    @Test
    fun allEditableFieldsHaveStorageLimits() {
        val details = CaseDetails(
            "x".repeat(2000), "x".repeat(2000), "x".repeat(2000), "9999", "x".repeat(2000),
            "x".repeat(2000), "x".repeat(2000), "x".repeat(2000), "x".repeat(2000),
        ).normalized()
        assertEquals(CaseFieldLimits.BITE_TIME, details.biteTime.length)
        assertEquals(CaseFieldLimits.BODY_PART, details.bodyPart.length)
        assertEquals(CaseFieldLimits.SYMPTOMS, details.symptoms.length)
        assertEquals(CaseFieldLimits.AGE, details.age.length)
        assertEquals(CaseFieldLimits.LOCATION, details.location.length)
        assertEquals(CaseFieldLimits.BLOOD_TYPE, details.bloodType.length)
        assertEquals(CaseFieldLimits.EMERGENCY_NAME, details.emergencyName.length)
        assertEquals(CaseFieldLimits.EMERGENCY_PHONE, details.emergencyPhone.length)
        assertEquals(CaseFieldLimits.EMERGENCY_RELATIONSHIP, details.emergencyRelationship.length)
    }

    @Test
    fun comparisonNeverDefaultsToFirstCandidateOrImpliesDiagnosis() {
        val draft = CaseDraft(bitten = false, candidateLabels = listOf("候选甲", "候选乙", "候选丙", "候选丁"))
        assertNull(draft.userComparison)
        assertEquals(3, draft.normalized().candidateLabels.size)
        assertNull(draft.copy(comparisonIndex = 9).normalized().userComparison)
        val selected = record(draft.copy(comparisonIndex = 2))
        assertTrue(selected.cardText().contains("用户比对选择：候选乙（不代表确诊）"))
        assertTrue(selected.cardText().contains("未被咬（用户填写）；不等于安全，请保持距离"))
        assertFalse(selected.toJson().has("diagnosis"))
        assertFalse(selected.toJson().has("venomType"))
    }

    @Test
    fun originalStateCannotClaimSavedWithoutBytesAndIdsCannotEscapeDirectory() {
        assertThrows(IllegalArgumentException::class.java) { CaseDraft(id = "../elsewhere", bitten = true) }
        assertThrows(IllegalArgumentException::class.java) {
            CaseRecord(CaseDraft(bitten = true), "2026-09-01T00:00:00Z", CaseOriginalStatus.SAVED)
        }
        val json = record(CaseDraft(bitten = false)).toJson().put("version", 99)
        assertThrows(IllegalArgumentException::class.java) { CaseRecord.fromJson(json) }
    }

    private fun record(draft: CaseDraft) = CaseRecord(
        draft.normalized(), "2026-09-01T00:00:00Z", CaseOriginalStatus.NOT_PROVIDED,
    )
}
