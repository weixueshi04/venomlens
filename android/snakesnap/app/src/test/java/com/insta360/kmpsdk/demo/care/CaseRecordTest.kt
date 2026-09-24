package com.insta360.kmpsdk.demo.care

import org.json.JSONArray
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
        val record = record(CaseDraft(biteStatus = BiteStatus.BITTEN, details = CaseDetails(" ", "", "\n", "", " ", " ", " ", " ", " ")))
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
            "【识别失败】\n错误码：UPSTREAM_TIMEOUT\n请求失败不等于没有蛇。",
            "pending · 识别处理中，尚无结果",
            "uncertain · 已给出候选；上游还提到本地未收录的物种，因此未作整体判定。",
            "用户拒绝上传；尚未识别",
        ).forEach { snapshot ->
            val result = CaseRecord.fromJson(record(CaseDraft(biteStatus = BiteStatus.BITTEN, recognitionSummary = snapshot)).toJson())
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
        val draft = CaseDraft(biteStatus = BiteStatus.NOT_BITTEN, candidateLabels = listOf("候选甲", "候选乙", "候选丙", "候选丁"))
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
        assertThrows(IllegalArgumentException::class.java) { CaseDraft(id = "../elsewhere", biteStatus = BiteStatus.BITTEN) }
        assertThrows(IllegalArgumentException::class.java) {
            CaseRecord(CaseDraft(biteStatus = BiteStatus.BITTEN), "2026-09-01T00:00:00Z", CaseOriginalStatus.SAVED)
        }
        val json = record(CaseDraft(biteStatus = BiteStatus.NOT_BITTEN)).toJson().put("version", 99)
        assertThrows(IllegalArgumentException::class.java) { CaseRecord.fromJson(json) }
    }

    @Test
    fun unknownBiteStatusNeverRendersAsASafetyClaim() {
        // 「用户没说」必须渲染成「未提供」。曾经这里是 Boolean 且缺失默认 false，
        // 卡片会替用户断言「未被咬（用户填写）」——在蛇伤场景里是一句会造成伤害的安全声明。
        val card = record(CaseDraft(biteStatus = BiteStatus.UNKNOWN)).cardText()
        assertTrue(card.contains("咬伤情况：未提供"))
        assertTrue(card.contains("未填写不代表未被咬"))
        assertFalse(card.contains("未被咬（用户填写）"))
    }

    @Test
    fun v1RecordsStillLoadAndMapBittenBoolean() {
        fun v1(bitten: Boolean?): JSONObject {
            val json = JSONObject()
                .put("version", 1)
                .put("id", "3f1c9b1e-0a2f-4c5d-8e6f-1a2b3c4d5e6f")
                .put("savedAt", "2026-09-01T00:00:00Z")
            if (bitten != null) json.put("bitten", bitten)
            return json
                .put("importedAt", JSONObject.NULL)
                .put("recognitionSummary", "【MOCK】uncertain")
                .put("candidateLabels", JSONArray())
                .put("comparisonIndex", 0)
                .put("details", CaseDetails().toJson())
                .put("originalStatus", CaseOriginalStatus.NOT_PROVIDED.name)
                .put("originalBytes", 0)
        }
        // v1 历史记录按它本来的意图读：true → BITTEN、false → NOT_BITTEN
        assertEquals(BiteStatus.BITTEN, CaseRecord.fromJson(v1(true)).draft.biteStatus)
        assertEquals(BiteStatus.NOT_BITTEN, CaseRecord.fromJson(v1(false)).draft.biteStatus)
        // 两个字段都缺才落 UNKNOWN——绝不默认成「未被咬」
        assertEquals(BiteStatus.UNKNOWN, CaseRecord.fromJson(v1(null)).draft.biteStatus)
    }

    @Test
    fun biteStatusSurvivesAV2RoundTrip() {
        BiteStatus.entries.forEach { status ->
            val restored = CaseRecord.fromJson(record(CaseDraft(biteStatus = status)).toJson())
            assertEquals(status, restored.draft.biteStatus)
        }
        assertEquals(
            CaseRecord.CASE_RECORD_JSON_VERSION,
            record(CaseDraft(biteStatus = BiteStatus.UNKNOWN)).toJson().getInt("version"),
        )
        // v2 不再写 bitten 字段：留着会让「未知」在旧读取方眼里又变成 false
        assertFalse(record(CaseDraft(biteStatus = BiteStatus.UNKNOWN)).toJson().has("bitten"))
    }

    private fun record(draft: CaseDraft) = CaseRecord(
        draft.normalized(), "2026-09-01T00:00:00Z", CaseOriginalStatus.NOT_PROVIDED,
    )
}
