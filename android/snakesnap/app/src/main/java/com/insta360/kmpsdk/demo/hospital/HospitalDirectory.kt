package com.insta360.kmpsdk.demo.hospital

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Only metadata is validated here. A human must verify the source before shipping an entry. */
data class HospitalEntry internal constructor(
    val hospitalId: String,
    val name: String,
    val address: String,
    val phone: String,
    val source: String,
    val checkedAt: String,
    val antivenomInfoStatus: String,
    val antivenomNote: String? = null,
) {
    val displayDetails: String
        get() = buildString {
            appendLine("地址：$address")
            appendLine("电话：$phone")
            appendLine("资料来源：$source")
            appendLine("资料核验日期：$checkedAt（不是库存更新时间）")
            if (antivenomInfoStatus == "historical_mention") {
                append("血清信息：仅有历史提及；历史提及≠适配当前蛇种。")
                append("不代表当前库存或接诊能力，请电话确认。")
                antivenomNote?.let {
                    append("\n历史资料备注（仅作历史记录，不作为当前救治依据）：「$it」")
                }
            } else {
                // An unclassified free-text note must not become a stock/availability claim.
                append("血清信息：未知；接诊及血清情况请电话确认。")
            }
        }
}

sealed class HospitalDirectoryResult {
    data class Loaded(
        val hospitals: List<HospitalEntry>,
        val rejectedEntries: Int = 0,
    ) : HospitalDirectoryResult()

    data class Failure(val reason: Reason) : HospitalDirectoryResult()

    enum class Reason { READ, FORMAT }
}

object HospitalDirectory {
    const val ASSET_NAME = "hospitals.json"
    const val MAINLAND_EMERGENCY_PHONE = "120"

    private val phoneCharacters = Regex("\\+?[0-9 ()-]+")
    private val dialNumber = Regex("\\+?[0-9]{3,15}")
    private val isoDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    /** Reject, rather than strip, USSD, encoded characters, URLs, extensions and controls. */
    fun normalizedPhone(phone: String): String? {
        if (phone.length > 64 || !phoneCharacters.matches(phone.trim(' '))) return null
        val number = phone.filter { it in '0'..'9' || it == '+' }
        return number.takeIf { dialNumber.matches(it) }
    }

    fun load(readAsset: () -> String, today: LocalDate = LocalDate.now()): HospitalDirectoryResult {
        val json = try {
            readAsset()
        } catch (_: IOException) {
            return HospitalDirectoryResult.Failure(HospitalDirectoryResult.Reason.READ)
        } catch (_: SecurityException) {
            return HospitalDirectoryResult.Failure(HospitalDirectoryResult.Reason.READ)
        }
        return parse(json, today)
    }

    fun parse(json: String, today: LocalDate = LocalDate.now()): HospitalDirectoryResult {
        return try {
            val tokener = JSONTokener(json)
            val root = tokener.nextValue() as? JSONObject ?: return formatFailure()
            if (tokener.nextClean() != '\u0000') return formatFailure()
            val entries = root.opt("hospitals") as? JSONArray ?: return formatFailure()
            val hospitals = mutableListOf<HospitalEntry>()
            val ids = mutableSetOf<String>()
            var rejected = 0
            for (index in 0 until entries.length()) {
                val entry = (entries.opt(index) as? JSONObject)?.let { parseEntry(it, today) }
                if (entry == null || !ids.add(entry.hospitalId)) {
                    rejected++
                } else {
                    hospitals += entry
                }
            }
            HospitalDirectoryResult.Loaded(hospitals.toList(), rejected)
        } catch (_: JSONException) {
            formatFailure()
        }
    }

    private fun parseEntry(value: JSONObject, today: LocalDate): HospitalEntry? {
        val id = value.text("hospitalId", 120) ?: return null
        val name = value.text("name", 200) ?: return null
        val address = value.text("address", 500) ?: return null
        val phone = (value.opt("phone") as? String)?.trim(' ') ?: return null
        if (normalizedPhone(phone) == null) return null
        // A non-empty, printable citation is required; this is not online source verification.
        val source = value.text("source", 1000) ?: return null
        val checkedAt = value.opt("checkedAt") as? String ?: return null
        if (!validDate(checkedAt, today)) return null
        val status = value.opt("antivenomInfoStatus") as? String ?: return null
        if (status != "unknown" && status != "historical_mention") return null
        val note = if (value.has("antivenomNote") && !value.isNull("antivenomNote")) {
            value.text("antivenomNote", 1000) ?: return null
        } else {
            null
        }
        return HospitalEntry(id, name, address, phone, source, checkedAt, status, note)
    }

    private fun validDate(value: String, today: LocalDate): Boolean {
        if (!isoDate.matches(value)) return false
        return try {
            val date = LocalDate.parse(value)
            date.year >= 1 && !date.isAfter(today)
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private fun JSONObject.text(key: String, maxLength: Int): String? {
        val value = opt(key) as? String ?: return null
        if (value.length > maxLength || value.any {
                Character.isISOControl(it) || Character.getType(it) in setOf(
                    Character.FORMAT.toInt(),
                    Character.LINE_SEPARATOR.toInt(),
                    Character.PARAGRAPH_SEPARATOR.toInt(),
                )
            }) return null
        return value.trim().takeIf { it.isNotBlank() }
    }

    private fun formatFailure() =
        HospitalDirectoryResult.Failure(HospitalDirectoryResult.Reason.FORMAT)
}
