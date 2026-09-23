package com.insta360.kmpsdk.demo.species

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URISyntaxException

class SpeciesCatalog private constructor(
    val entries: List<SpeciesEntry>,
    val rejectedEntries: Int,
) {
    fun find(speciesId: String?): SpeciesEntry? = entries.firstOrNull { it.speciesId == speciesId }

    companion object {
        const val ASSET_PATH = "data/species.json"
        const val MAX_JSON_CHARACTERS = 1_048_576
        const val SAFETY_WARNING = "仅用于比对照片，不能判断有没有毒；被咬伤立即就医。"
        const val REVIEW_WARNING = "比对文案待审核；不作为医疗或毒性判断依据。"
        const val DRAFT_WARNING = "演示草稿／待核验，按未知处理"
        private val identifier = Regex("[a-z0-9]+(?:_[a-z0-9]+)*")
        private val pathSegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private val encodedUnsafe = Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f|5c|25)", RegexOption.IGNORE_CASE)

        fun parse(json: String): SpeciesCatalog {
            require(json.length <= MAX_JSON_CHARACTERS) { "Catalog too large" }
            try {
                val tokener = JSONTokener(json)
                val root = tokener.nextValue() as? JSONArray
                    ?: throw IllegalArgumentException("Expected species array")
                require(tokener.nextClean() == '\u0000') { "Trailing catalog data" }
                require(root.length() <= 256) { "Too many species" }
                val ids = mutableSetOf<String>()
                val entries = mutableListOf<SpeciesEntry>()
                var rejected = 0
                for (index in 0 until root.length()) {
                    val entry = (root.opt(index) as? JSONObject)?.let(::parseEntry)
                    if (entry == null || !ids.add(entry.speciesId)) rejected++ else entries += entry
                }
                return SpeciesCatalog(entries.toList(), rejected)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Invalid species catalog", error)
            }
        }

        fun safeReferencePath(speciesId: String, path: String?): String? {
            if (!validId(speciesId) || path == null || path.length > 512) return null
            val prefix = "data/reference_images/$speciesId/"
            if (!path.startsWith(prefix)) return null
            val segments = path.removePrefix(prefix).split('/')
            if (segments.any { !pathSegment.matches(it) || it == "." || it == ".." }) return null
            return path
        }

        fun safeExternalUrl(value: String?): String? {
            if (value.isNullOrEmpty() || value.length > 2048 || value.any {
                    unsafeCharacter(it) || it.isWhitespace() || it == '\\'
                } || encodedUnsafe.containsMatchIn(value)) return null
            return try {
                val uri = URI(value)
                value.takeIf {
                    uri.scheme.equals("https", ignoreCase = true) && !uri.isOpaque &&
                        !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                        (uri.port == -1 || uri.port in 1..65535) &&
                        uri.schemeSpecificPart.none(::unsafeCharacter) &&
                        uri.fragment.orEmpty().none(::unsafeCharacter)
                }
            } catch (_: URISyntaxException) {
                null
            }
        }

        private fun parseEntry(value: JSONObject): SpeciesEntry? {
            val id = value.text("speciesId", 100)?.takeIf(::validId) ?: return null
            val commonName = value.text("commonName", 200) ?: return null
            val scientificName = value.text("scientificName", 200) ?: return null
            val aliases = value.array("aliases").objects().mapNotNull { alias ->
                alias.text("alias", 200)?.let {
                    SpeciesAlias(it, alias.text("region"), alias.text("level"), alias.text("source"))
                }
            }
            val images = value.array("referenceImages").objects().map { image ->
                ReferenceImage(
                    file = safeReferencePath(id, image.opt("file") as? String),
                    role = image.text("role"),
                    source = image.text("source"),
                    rights = image.text("rights"),
                    sourcePage = image.text("sourcePage", 2048),
                    note = image.text("note"),
                    attributionConsistent = image.opt("attributionConsistent") != false,
                )
            }
            val links = value.array("externalRefs")
            val externalRefs = links.objects().mapNotNull { link ->
                val url = safeExternalUrl(link.opt("url") as? String) ?: return@mapNotNull null
                ExternalReference(link.text("name", 200) ?: "外部参考资料", url)
            }
            val profile = (value.opt("comparisonProfile") as? JSONObject)?.let { profile ->
                ComparisonProfile(
                    hook = profile.text("hook"),
                    layChecklist = profile.array("layChecklist").texts(),
                    doNot = profile.array("doNot").texts(),
                    layLookAlikes = profile.array("layLookAlikes").objects().mapNotNull { similar ->
                        val similarId = similar.text("speciesId", 100)?.takeIf(::validId)
                            ?: return@mapNotNull null
                        val observation = similar.text("layHowToTell") ?: return@mapNotNull null
                        LayLookAlike(similarId, observation)
                    },
                ).takeUnless { it.isEmpty }
            }
            return SpeciesEntry(
                id, commonName, scientificName, aliases,
                value.text("verificationStatus", 100) ?: "unknown",
                value.opt("demoDraft") == true, value.text("source"),
                profile, images, externalRefs, links.length() - externalRefs.size,
            )
        }

        private fun validId(value: String) = value.length <= 100 && identifier.matches(value)

        private fun unsafeCharacter(value: Char): Boolean = Character.isISOControl(value) ||
            Character.getType(value) in setOf(
                Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            )

        private fun text(value: Any?, maxLength: Int = 2000): String? = (value as? String)?.takeIf {
            it.length <= maxLength && it.none(::unsafeCharacter)
        }?.trim()?.takeIf { it.isNotEmpty() }

        private fun JSONObject.text(key: String, maxLength: Int = 2000) = text(opt(key), maxLength)

        private fun JSONObject.array(key: String): JSONArray =
            (opt(key) as? JSONArray ?: JSONArray()).also { require(it.length() <= 64) { "Too many details" } }

        private fun JSONArray.objects(): List<JSONObject> =
            (0 until length()).mapNotNull { opt(it) as? JSONObject }

        private fun JSONArray.texts(): List<String> = (0 until length()).mapNotNull { text(opt(it)) }
    }
}

data class SpeciesEntry internal constructor(
    val speciesId: String,
    val commonName: String,
    val scientificName: String,
    val aliases: List<SpeciesAlias>,
    val verificationStatus: String,
    val demoDraft: Boolean,
    val source: String?,
    val comparisonProfile: ComparisonProfile?,
    val referenceImages: List<ReferenceImage>,
    val externalRefs: List<ExternalReference>,
    val rejectedExternalRefs: Int,
) {
    val reviewNotice: String
        get() = buildString {
            append(SpeciesCatalog.REVIEW_WARNING)
            if (verificationStatus == "verified") {
                append("\n名称映射已核验，不表示医疗或描述资料已经审核；按未知处理。")
            }
            if (demoDraft || verificationStatus != "verified") append("\n${SpeciesCatalog.DRAFT_WARNING}")
        }
}

data class SpeciesAlias internal constructor(val alias: String, val region: String?, val level: String?, val source: String?)

data class ComparisonProfile internal constructor(
    val hook: String?,
    val layChecklist: List<String>,
    val doNot: List<String>,
    val layLookAlikes: List<LayLookAlike>,
) {
    val isEmpty: Boolean get() = hook == null && layChecklist.isEmpty() && doNot.isEmpty() && layLookAlikes.isEmpty()
}

data class LayLookAlike internal constructor(val speciesId: String, val layHowToTell: String)

data class ReferenceImage internal constructor(
    val file: String?,
    val role: String?,
    val source: String?,
    val rights: String?,
    val sourcePage: String?,
    val note: String?,
    val attributionConsistent: Boolean,
) {
    val hasAttribution: Boolean get() = !source.isNullOrBlank() && !rights.isNullOrBlank()
    val canLoad: Boolean get() = file != null && hasAttribution && attributionConsistent
}

data class ExternalReference internal constructor(val name: String, val url: String)

enum class ComparisonResultSource(val badge: String) {
    MOCK("结果来源：MOCK · 模拟结果"),
    LIVE("结果来源：LIVE · 在线结果（不代表核验）"),
    CACHE("结果来源：CACHE · 缓存结果"),
    UNKNOWN("结果来源：UNKNOWN · 未知来源，按未知处理");

    companion object {
        fun from(value: String?): ComparisonResultSource = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}
