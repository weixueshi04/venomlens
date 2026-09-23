package com.insta360.kmpsdk.demo.care

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object CaseFieldLimits {
    const val BITE_TIME = 80
    const val BODY_PART = 120
    const val SYMPTOMS = 1000
    const val AGE = 3
    const val LOCATION = 200
    const val BLOOD_TYPE = 10
    const val EMERGENCY_NAME = 60
    const val EMERGENCY_PHONE = 30
    const val EMERGENCY_RELATIONSHIP = 30
}

data class CaseDetails(
    val biteTime: String = "",
    val bodyPart: String = "",
    val symptoms: String = "",
    val age: String = "",
    val location: String = "",
    val bloodType: String = "",
    val emergencyName: String = "",
    val emergencyPhone: String = "",
    val emergencyRelationship: String = "",
) {
    fun normalized() = CaseDetails(
        biteTime.trim().take(CaseFieldLimits.BITE_TIME),
        bodyPart.trim().take(CaseFieldLimits.BODY_PART),
        symptoms.trim().take(CaseFieldLimits.SYMPTOMS),
        age.trim().take(CaseFieldLimits.AGE),
        location.trim().take(CaseFieldLimits.LOCATION),
        bloodType.trim().take(CaseFieldLimits.BLOOD_TYPE),
        emergencyName.trim().take(CaseFieldLimits.EMERGENCY_NAME),
        emergencyPhone.trim().take(CaseFieldLimits.EMERGENCY_PHONE),
        emergencyRelationship.trim().take(CaseFieldLimits.EMERGENCY_RELATIONSHIP),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("biteTime", biteTime)
        .put("bodyPart", bodyPart)
        .put("symptoms", symptoms)
        .put("age", age)
        .put("location", location)
        .put("bloodType", bloodType)
        .put("emergencyName", emergencyName)
        .put("emergencyPhone", emergencyPhone)
        .put("emergencyRelationship", emergencyRelationship)

    companion object {
        fun fromJson(json: JSONObject) = CaseDetails(
            json.optString("biteTime"), json.optString("bodyPart"),
            json.optString("symptoms"), json.optString("age"), json.optString("location"),
            json.optString("bloodType"), json.optString("emergencyName"),
            json.optString("emergencyPhone"), json.optString("emergencyRelationship"),
        )
    }
}

data class CaseDraft(
    val id: String = UUID.randomUUID().toString(),
    val bitten: Boolean,
    val importedAt: String? = null,
    val recognitionSummary: String = "",
    val candidateLabels: List<String> = emptyList(),
    val comparisonIndex: Int = 0,
    val details: CaseDetails = CaseDetails(),
) {
    init {
        require(isValidId(id))
    }

    val userComparison: String?
        get() = candidateLabels.getOrNull(comparisonIndex - 1)

    fun normalized(): CaseDraft {
        val labels = candidateLabels.take(3)
        return copy(
            details = details.normalized(),
            candidateLabels = labels,
            comparisonIndex = comparisonIndex.takeIf { it in 1..labels.size } ?: 0,
        )
    }

    companion object {
        fun isValidId(id: String): Boolean =
            id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    }
}

enum class CaseOriginalStatus {
    NOT_PROVIDED,
    SAVED,
    OMITTED_AFTER_FAILURE,
}

data class CaseRecord(
    val draft: CaseDraft,
    val savedAt: String,
    val originalStatus: CaseOriginalStatus,
    val originalBytes: Long = 0,
) {
    val id: String get() = draft.id

    init {
        require(if (originalStatus == CaseOriginalStatus.SAVED) originalBytes > 0 else originalBytes == 0L)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("id", id)
        .put("savedAt", savedAt)
        .put("bitten", draft.bitten)
        .put("importedAt", draft.importedAt ?: JSONObject.NULL)
        .put("recognitionSummary", draft.recognitionSummary)
        .put("candidateLabels", JSONArray(draft.candidateLabels))
        .put("comparisonIndex", draft.comparisonIndex)
        .put("details", draft.details.toJson())
        .put("originalStatus", originalStatus.name)
        .put("originalBytes", originalBytes)

    fun cardText(): String = buildString {
        appendLine("本地伤情信息卡 · 非诊断")
        appendLine(if (draft.bitten) "被咬（用户填写）" else "未被咬（用户填写）；不等于安全，请保持距离。")
        appendLine("记录时间：$savedAt")
        appendLine("图片导入时间：${provided(draft.importedAt)}")
        appendLine("咬伤时间：${provided(draft.details.biteTime)}")
        appendLine("部位：${provided(draft.details.bodyPart)}")
        appendLine("症状：${provided(draft.details.symptoms)}")
        appendLine("年龄：${provided(draft.details.age)}")
        appendLine("血型：${provided(draft.details.bloodType)}")
        appendLine("手填位置：${provided(draft.details.location)}")
        appendLine("紧急联系人：${provided(draft.details.emergencyName)}")
        appendLine("联系人电话：${provided(draft.details.emergencyPhone)}")
        appendLine("联系人关系：${provided(draft.details.emergencyRelationship)}")
        appendLine("\n用户比对选择：${draft.userComparison ?: "未比对"}（不代表确诊）")
        appendLine("本信息卡不附物种参考图；未判断毒型。风险未知，须由医师判断。")
        appendLine("\n识别快照（保留来源与状态，不作为诊断）：")
        appendLine(provided(draft.recognitionSummary))
        append("\n仅存本机，不上传，不支持分享或导出。")
    }

    companion object {
        private fun provided(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "未提供"

        fun fromJson(json: JSONObject): CaseRecord {
            require(json.getInt("version") == 1)
            val labels = json.getJSONArray("candidateLabels")
            require(labels.length() <= 3)
            val draft = CaseDraft(
                id = json.getString("id"),
                bitten = json.getBoolean("bitten"),
                importedAt = if (json.isNull("importedAt")) null else json.getString("importedAt"),
                recognitionSummary = json.getString("recognitionSummary"),
                candidateLabels = List(labels.length()) { labels.getString(it) },
                comparisonIndex = json.getInt("comparisonIndex"),
                details = CaseDetails.fromJson(json.getJSONObject("details")),
            )
            require(draft.comparisonIndex in 0..draft.candidateLabels.size)
            return CaseRecord(
                draft, json.getString("savedAt"),
                CaseOriginalStatus.valueOf(json.getString("originalStatus")),
                json.getLong("originalBytes"),
            )
        }
    }
}
