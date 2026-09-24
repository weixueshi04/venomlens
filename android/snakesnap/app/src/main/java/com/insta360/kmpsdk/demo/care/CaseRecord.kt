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

/**
 * 咬伤情况。
 *
 * **刻意做成三态**：`UNKNOWN` 与 `NOT_BITTEN` 是两件完全不同的事，不能合并。
 *
 * 这里原先是一个 `Boolean`，而传入路径 `CaseRecordActivity.restoreDraft()` 用的是
 * `getBooleanExtra(EXTRA_BITTEN, false)`——意图缺失时会默认落成 `false`，
 * 于是整条链路把「用户没说」渲染成了「未被咬（用户填写）」这句**安全声明**。
 * 蛇伤场景里「画面里没识别到爬行动物」根本不构成「没有被咬」的证据，
 * 把未知说成安全属于产品红线问题，所以改为显式三态：未知就老实输出「未提供」。
 */
enum class BiteStatus {
    UNKNOWN,
    BITTEN,
    NOT_BITTEN,
}

data class CaseDraft(
    val id: String = UUID.randomUUID().toString(),
    val biteStatus: BiteStatus = BiteStatus.UNKNOWN,
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
        .put("version", CASE_RECORD_JSON_VERSION)
        .put("id", id)
        .put("savedAt", savedAt)
        .put("biteStatus", draft.biteStatus.name)
        .put("importedAt", draft.importedAt ?: JSONObject.NULL)
        .put("recognitionSummary", draft.recognitionSummary)
        .put("candidateLabels", JSONArray(draft.candidateLabels))
        .put("comparisonIndex", draft.comparisonIndex)
        .put("details", draft.details.toJson())
        .put("originalStatus", originalStatus.name)
        .put("originalBytes", originalBytes)

    fun cardText(): String = buildString {
        appendLine("本地伤情信息卡 · 非诊断")
        appendLine(
            when (draft.biteStatus) {
                BiteStatus.BITTEN -> "被咬（用户填写）"
                BiteStatus.NOT_BITTEN -> "未被咬（用户填写）；不等于安全，请保持距离。"
                // 未填写 ≠ 未被咬。写成「未提供」，避免把未知渲染成一句安全声明。
                BiteStatus.UNKNOWN -> "咬伤情况：未提供（未填写不代表未被咬，请保持距离）"
            },
        )
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
        /** 当前落盘版本。v1 → v2 的唯一变化是把 `bitten: Boolean` 换成三态 `biteStatus`。 */
        const val CASE_RECORD_JSON_VERSION = 2

        private fun provided(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "未提供"

        /**
         * 读取 v1 与 v2。
         *
         * v1 只有 `bitten: Boolean`，映射时**不允许**把 `false` 直接当成「未被咬」以外的东西——
         * v1 里 `false` 的语义本身就已经被污染（见 [BiteStatus] 的说明），但历史记录只能按它本来的
         * 意图读：`true → BITTEN`、`false → NOT_BITTEN`。缺失字段才落 [BiteStatus.UNKNOWN]。
         */
        fun fromJson(json: JSONObject): CaseRecord {
            val version = json.optInt("version", 1)
            require(version == 1 || version == CASE_RECORD_JSON_VERSION) {
                "unsupported case record version: $version"
            }
            val labels = json.getJSONArray("candidateLabels")
            require(labels.length() <= 3)
            val draft = CaseDraft(
                id = json.getString("id"),
                biteStatus = readBiteStatus(json, version),
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

        private fun readBiteStatus(json: JSONObject, version: Int): BiteStatus {
            if (version >= CASE_RECORD_JSON_VERSION && json.has("biteStatus")) {
                return runCatching { BiteStatus.valueOf(json.getString("biteStatus")) }
                    .getOrDefault(BiteStatus.UNKNOWN)
            }
            if (!json.has("bitten") || json.isNull("bitten")) return BiteStatus.UNKNOWN
            return if (json.getBoolean("bitten")) BiteStatus.BITTEN else BiteStatus.NOT_BITTEN
        }
    }
}
