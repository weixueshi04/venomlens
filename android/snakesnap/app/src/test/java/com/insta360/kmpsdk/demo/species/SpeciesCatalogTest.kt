package com.insta360.kmpsdk.demo.species

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SpeciesCatalogTest {
    @Test
    fun currentV2CatalogHasSixEntriesAndRetainsNamesSourcesAndAttribution() {
        val json = currentV2Json()
        val catalog = SpeciesCatalog.parse(json)
        assertEquals(0, catalog.rejectedEntries)
        assertEquals(
            listOf("玉米蛇", "加州王蛇", "颈棱蛇", "猪鼻蛇", "赤链蛇", "短尾蝮"),
            catalog.entries.map { it.commonName },
        )
        assertEquals(6, catalog.entries.size)
        val raw = JSONArray(json)
        for (index in 0 until raw.length()) {
            val original = raw.getJSONObject(index)
            val entry = requireNotNull(catalog.find(original.getString("speciesId")))
            assertEquals(original.getString("commonName"), entry.commonName)
            assertEquals(original.getString("scientificName"), entry.scientificName)
            assertEquals(original.opt("source") as? String, entry.source)
            assertEquals(original.getString("verificationStatus"), entry.verificationStatus)
            val images = original.getJSONArray("referenceImages")
            assertEquals(images.length(), entry.referenceImages.size)
            entry.referenceImages.forEachIndexed { imageIndex, image ->
                val originalImage = images.getJSONObject(imageIndex)
                assertEquals(originalImage.getString("file"), image.file)
                assertEquals(originalImage.getString("role"), image.role)
                assertEquals(originalImage.getString("source"), image.source)
                assertEquals(originalImage.getString("rights"), image.rights)
                assertEquals(originalImage.opt("sourcePage") as? String, image.sourcePage)
                assertTrue(image.canLoad)
            }
            assertEquals(original.getJSONArray("externalRefs").length(), entry.externalRefs.size)
            assertEquals(0, entry.rejectedExternalRefs)
            assertTrue(entry.reviewNotice.contains(SpeciesCatalog.REVIEW_WARNING))
        }
        val rudis = requireNotNull(catalog.find("pseudagkistrodon_rudis"))
        assertEquals("Pseudagkistrodon rudis", rudis.scientificName)
        assertEquals("伪腹蛇", rudis.aliases.single().alias)
        assertEquals("郭浩天 2026-09-22 提供原文，保留待复核", rudis.aliases.single().source)
        assertEquals("verified", rudis.verificationStatus)
        assertTrue(rudis.reviewNotice.contains("不表示医疗或描述资料已经审核"))
        assertNull(catalog.find("lampropeltis_californiae")?.comparisonProfile)
        assertEquals("团队自有", catalog.find("pantherophis_guttatus")?.referenceImages?.single()?.rights)
        listOf("heterodon_nasicus", "lycodon_rufozonatus", "gloydius_brevicaudus").forEach {
            val draft = requireNotNull(catalog.find(it))
            assertTrue(draft.demoDraft)
            assertTrue(draft.reviewNotice.contains(SpeciesCatalog.DRAFT_WARNING))
        }
    }

    @Test
    fun absentNullEmptyAndTechOnlyProfilesRemainExplicitlyAbsent() {
        listOf(null, JSONObject.NULL, JSONObject(), JSONObject().put("techNotes", "不可展示技术笔记"),
            JSONObject().put("hook", " ").put("layChecklist", JSONArray())).forEach { profile ->
            val entry = fixture().put("englishCommonName", "Never a description")
            if (profile != null) entry.put("comparisonProfile", profile)
            val parsed = parseEntry(entry)
            assertNull(parsed.comparisonProfile)
            assertFalse(parsed.toString().contains("Never a description"))
            assertFalse(parsed.toString().contains("不可展示技术笔记"))
        }
    }

    @Test
    fun layProfileContainsOnlyAllowedObservations() {
        val profile = JSONObject().put("hook", "测试观察标题")
            .put("layChecklist", JSONArray().put("测试照片观察"))
            .put("doNot", JSONArray().put("不要靠近"))
            .put("layLookAlikes", JSONArray().put(JSONObject().put("speciesId", "other_species")
                .put("layHowToTell", "分不清按未知处理")))
            .put("techNotes", JSONObject().put("secret", "不应渲染"))
        val parsed = requireNotNull(parseEntry(fixture().put("comparisonProfile", profile)).comparisonProfile)
        assertEquals("测试观察标题", parsed.hook)
        assertEquals(listOf("测试照片观察"), parsed.layChecklist)
        assertEquals(listOf("不要靠近"), parsed.doNot)
        assertEquals("other_species", parsed.layLookAlikes.single().speciesId)
        assertFalse(parsed.toString().contains("不应渲染"))
    }

    @Test
    fun demoDraftAndPendingReviewNeverInheritNameVerificationAsContentReview() {
        listOf("verified", "pending_review", "unexpected").forEach { status ->
            val entry = parseEntry(fixture().put("verificationStatus", status).put("demoDraft", true))
            assertEquals(status, entry.verificationStatus)
            assertTrue(entry.reviewNotice.contains(SpeciesCatalog.DRAFT_WARNING))
            assertTrue(entry.reviewNotice.contains(SpeciesCatalog.REVIEW_WARNING))
        }
        assertTrue(parseEntry(fixture()).reviewNotice.contains(SpeciesCatalog.DRAFT_WARNING))
    }

    @Test
    fun missingAndUnknownSpeciesDoNotInventAnEntry() {
        val catalog = SpeciesCatalog.parse(JSONArray().put(fixture()).toString())
        assertNull(catalog.find(null))
        assertNull(catalog.find(""))
        assertNull(catalog.find("unknown_species"))
        assertTrue(SpeciesCatalog.parse("[]").entries.isEmpty())
    }

    @Test
    fun imagePathsAreConfinedToTheExactSpeciesDirectory() {
        // 发布层（脱敏 card_images）才是唯一可打进 assets 的图层，路径白名单随之收紧
        val base = "${SpeciesCatalog.CARD_IMAGE_ROOT}/test_species/"
        listOf("photo.jpg", "sub/ref_01.png").forEach {
            assertEquals(base + it, SpeciesCatalog.safeReferencePath("test_species", base + it))
        }
        listOf(null, "", "/${base}photo.jpg", "E:/${base}photo.jpg", "https://example.invalid/image.jpg",
            "file:///${base}photo.jpg", "data/card_images/other_species/photo.jpg",
            // 原始图层（reference_images）带 EXIF、不随包发布，必须与不安全路径一样被拒绝
            "data/reference_images/test_species/photo.jpg",
            "data/reference_images/test_species/ref_01_head_coiled.jpg",
            "${base}../photo.jpg",
            "${base}sub/../../photo.jpg", "${base}./photo.jpg", "${base}sub//photo.jpg", "${base}photo.jpg/",
            "${base}\\photo.jpg", "${base}%2e%2e/photo.jpg", "${base}%252e%252e/photo.jpg", "${base}photo.jpg?x=1",
            "${base}photo.jpg#x", "${base}photo.jpg\n", "${base}photo.jpg\u0000", "${base}photo.jpg:stream").forEach {
            assertNull("Accepted unsafe path: $it", SpeciesCatalog.safeReferencePath("test_species", it))
            val image = parseEntry(withImage(image().put("file", it ?: JSONObject.NULL))).referenceImages.single()
            assertNull(image.file)
            assertFalse(image.canLoad)
            assertEquals("团队自有", image.rights)
        }
        assertNull(SpeciesCatalog.safeReferencePath("../test_species", base + "photo.jpg"))
    }

    @Test
    fun absentOrInvalidAttributionFailsClosedWithoutDiscardingOtherMetadata() {
        listOf("source", "rights").forEach { field ->
            val missing = image().apply { remove(field) }
            assertFalse(parseEntry(withImage(missing)).referenceImages.single().canLoad)
            listOf(JSONObject.NULL, "", "   ", 42, "来源\u0000", "来源\u202E").forEach { invalid ->
                val parsed = parseEntry(withImage(image().put(field, invalid))).referenceImages.single()
                assertFalse(parsed.hasAttribution)
                assertFalse(parsed.canLoad)
                assertEquals("测试主图", parsed.role)
                assertEquals("https://example.invalid/observation", parsed.sourcePage)
            }
        }
    }

    @Test
    fun conflictingAttributionCannotLoadEvenWithRightsAndSource() {
        val reference = parseEntry(withImage(image().put("attributionConsistent", false))).referenceImages.single()
        assertTrue(reference.hasAttribution)
        assertFalse(reference.canLoad)
    }

    @Test
    fun externalReferencesRejectNonHttpsUserInfoControlsAndAmbiguousUrls() {
        val invalid = listOf(null, "", "http://example.invalid", "file:///tmp/a", "javascript:alert(1)",
            "intent://example.invalid", "//example.invalid", "https:example.invalid", "https:///a",
            "https://user@example.invalid", "https://user:pass@example.invalid", "https://@example.invalid",
            "https://user%40example.invalid@evil.invalid", "https://example.invalid\\@evil.invalid",
            " https://example.invalid", "https://example.invalid\n", "https://example.invalid/\u0000",
            "https://example.invalid/%0d%0aLocation:evil", "https://example.invalid/%00",
            "https://example.invalid/%250a", "https://example.invalid/%5c", "https://example.invalid/%C2%85",
            "https://example.invalid/%E2%80%AE", "https://example.invalid:70000/", "https://example.invalid/%zz")
        invalid.forEach {
            assertNull("Accepted unsafe link: $it", SpeciesCatalog.safeExternalUrl(it))
            val parsed = parseEntry(fixture().put("externalRefs", JSONArray().put(JSONObject()
                .put("name", "测试链接").put("url", it ?: JSONObject.NULL))))
            assertTrue(parsed.externalRefs.isEmpty())
            assertEquals(1, parsed.rejectedExternalRefs)
        }
    }

    @Test
    fun validHttpsReferencesArePreservedWithoutFetching() {
        listOf("https://www.inaturalist.org/observations/396535501", "https://baike.baidu.com/item/颈棱蛇",
            "https://example.invalid/%E9%A2%88", "https://example.invalid:443/page?a=b#source").forEach {
            assertEquals(it, SpeciesCatalog.safeExternalUrl(it))
        }
    }

    @Test
    fun sourceBadgeAcceptsOnlyExplicitKnownSourceAndNeverDefaultsToLive() {
        assertEquals(ComparisonResultSource.MOCK, ComparisonResultSource.from("MOCK"))
        assertEquals(ComparisonResultSource.LIVE, ComparisonResultSource.from("LIVE"))
        assertEquals(ComparisonResultSource.CACHE, ComparisonResultSource.from("CACHE"))
        listOf(null, "", "live", " LIVE", "unexpected", "null").forEach {
            assertEquals(ComparisonResultSource.UNKNOWN, ComparisonResultSource.from(it))
            assertFalse(ComparisonResultSource.from(it).badge.contains("LIVE"))
        }
    }

    @Test
    fun malformedCatalogsAndOversizedInputsFailExplicitly() {
        listOf("", "{", "{}", "null", "[] trailing", " ".repeat(SpeciesCatalog.MAX_JSON_CHARACTERS + 1)).forEach {
            try {
                SpeciesCatalog.parse(it)
                fail("Malformed catalog accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        val catalog = SpeciesCatalog.parse(JSONArray().put(fixture()).put(fixture()).put(JSONObject()).toString())
        assertEquals(1, catalog.entries.size)
        assertEquals(2, catalog.rejectedEntries)
    }

    @Test
    fun samplingRejectsInvalidDimensionsAndCapsDecodedSizeAndMemory() {
        listOf(0 to 100, -1 to 100, 50_001 to 1, 50_000 to 50_000).forEach { (width, height) ->
            assertNull(OfflineReferenceImages.sampleSize(width, height, 8 * 1024 * 1024))
        }
        assertNull(OfflineReferenceImages.sampleSize(100, 100, 0))
        listOf(100 to 100, 6000 to 4000, 1 to 50_000, 20_000 to 10_000).forEach { (width, height) ->
            val sample = OfflineReferenceImages.sampleSize(width, height, 1024 * 1024)
            assertNotNull(sample)
            val decodedWidth = (width + sample!! - 1) / sample
            val decodedHeight = (height + sample - 1) / sample
            assertTrue(decodedWidth <= 1024 && decodedHeight <= 1024)
            assertTrue(decodedWidth.toLong() * decodedHeight * 4 <= 1024 * 1024)
        }
    }

    private fun currentV2Json(): String {
        val explicit = System.getProperty("speciesCatalogFile") ?: System.getenv("SPECIES_CATALOG_FILE")
        val file = explicit?.let(::File) ?: File(
            File(requireNotNull(System.getProperty("recognitionFixtures")) {
                "Set SPECIES_CATALOG_FILE to the v2 species.json or use the app test task"
            }).parentFile,
            SpeciesCatalog.ASSET_PATH,
        )
        check(file.isFile) { "Integrate v2 data/species.json into APK assets or set SPECIES_CATALOG_FILE: $file" }
        return file.readText(Charsets.UTF_8)
    }

    private fun fixture() = JSONObject().put("speciesId", "test_species").put("commonName", "测试物种")
        .put("scientificName", "Test species").put("verificationStatus", "pending_review")
        .put("source", "仅测试来源")

    private fun image() = JSONObject().put("file", "${SpeciesCatalog.CARD_IMAGE_ROOT}/test_species/photo.jpg")
        .put("role", "测试主图").put("source", "测试拍摄团队").put("rights", "团队自有")
        .put("sourcePage", "https://example.invalid/observation")

    private fun withImage(image: JSONObject) = fixture().put("referenceImages", JSONArray().put(image))

    private fun parseEntry(entry: JSONObject) = SpeciesCatalog.parse(JSONArray().put(entry).toString()).entries.single()
}
