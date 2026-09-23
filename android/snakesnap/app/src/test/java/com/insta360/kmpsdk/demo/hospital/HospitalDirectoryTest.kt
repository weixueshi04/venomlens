package com.insta360.kmpsdk.demo.hospital

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class HospitalDirectoryTest {
    private val today = LocalDate.of(2025, 1, 31)

    @Test
    fun emptyDirectoryIsSuccessfulAndHasNoRejections() {
        val result = HospitalDirectory.parse("{\"hospitals\":[]}", today) as HospitalDirectoryResult.Loaded
        assertTrue(result.hospitals.isEmpty())
        assertEquals(0, result.rejectedEntries)
    }

    @Test
    fun fictionalVerifiedMetadataProducesDisplayData() {
        val hospital = loaded(fixture()).hospitals.single()
        assertEquals("fictional-test-only", hospital.hospitalId)
        assertEquals("测试虚构医院（非真实医疗机构）", hospital.name)
        assertEquals("测试虚构地址（请勿前往）", hospital.address)
        assertEquals("+1 (202) 555-0100", hospital.phone)
        assertEquals("https://hospital-fixture.invalid/source", hospital.source)
        assertEquals("2024-02-29", hospital.checkedAt)
        assertEquals("unknown", hospital.antivenomInfoStatus)
        assertNull(hospital.antivenomNote)
        assertTrue(hospital.displayDetails.contains("资料来源：${hospital.source}"))
        assertTrue(hospital.displayDetails.contains("2024-02-29（不是库存更新时间）"))
        assertTrue(hospital.displayDetails.contains("血清信息：未知"))
        assertTrue(hospital.displayDetails.contains("请电话确认"))
    }

    @Test
    fun requiredFieldsCannotBeMissingOrNull() {
        listOf("hospitalId", "name", "address", "phone", "source", "checkedAt", "antivenomInfoStatus")
            .forEach { field ->
                assertRejected(fixture().apply { remove(field) })
                assertRejected(fixture().put(field, JSONObject.NULL))
            }
    }

    @Test
    fun sourceMustBeNonblankPrintableText() {
        listOf("", "   ", "\n", 123, "来源\u202E伪装", "来源\u0000").forEach {
            assertRejected(fixture().put("source", it))
        }
    }

    @Test
    fun checkedAtMustBeRealIsoDateAndNotInFuture() {
        listOf("2024-02-30", "2023-02-29", "2024-13-01", "2024-00-01", "2024-01-00",
            "2024-2-01", "2024/02/01", "2024-02-01T00:00:00Z", " 2024-02-01 ",
            "0000-01-01", "2025-02-01", "unknown", "", 20240201).forEach {
            assertRejected(fixture().put("checkedAt", it))
        }
        assertEquals(1, loaded(fixture().put("checkedAt", today.toString())).hospitals.size)
    }

    @Test
    fun formattedPhonesAndEmergencyNumberNormalizeWithoutDialing() {
        mapOf("+1 (202) 555-0100" to "+12025550100", "120" to "120",
            " 202-555-0100 " to "2025550100").forEach { (input, expected) ->
            assertEquals(expected, HospitalDirectory.normalizedPhone(input))
            assertEquals(1, loaded(fixture().put("phone", input)).hospitals.size)
        }
    }

    @Test
    fun dangerousPhoneCharactersAreRejectedRatherThanSanitized() {
        listOf("*123#", "123#", "123*", "%2A123%23", "tel:120", "https://example.invalid/120",
            "intent://120", "120;123", "120,123", "120?x=1", "120&x=1", "120/123", "120\\123",
            "120\n", "120\r", "120\t", "\u2000120", "１２０", "+", "++120", "12+0",
            "12", "1234567890123456", "", "   ", "---").forEach { phone ->
            assertNull("Unsafe phone accepted: $phone", HospitalDirectory.normalizedPhone(phone))
            assertRejected(fixture().put("phone", phone))
        }
    }

    @Test
    fun onlyUnknownAndHistoricalMentionStatusesAreAccepted() {
        listOf("available", "in_stock", "confirmed", "live", "unknown ", "", 1).forEach {
            assertRejected(fixture().put("antivenomInfoStatus", it))
        }
        assertEquals(1, loaded(fixture().put("antivenomInfoStatus", "historical_mention")).hospitals.size)
    }

    @Test
    fun historicalNotesRemainLiteralAndExplicitlyNotCurrentOrSpeciesSpecific() {
        val note = "<b>仅测试历史提及</b> https://note-fixture.invalid/record"
        val hospital = loaded(fixture().put("antivenomInfoStatus", "historical_mention")
            .put("antivenomNote", note)).hospitals.single()
        assertEquals(note, hospital.antivenomNote)
        assertTrue(hospital.displayDetails.contains("历史提及≠适配当前蛇种"))
        assertTrue(hospital.displayDetails.contains("不代表当前库存或接诊能力"))
        assertTrue(hospital.displayDetails.contains("仅作历史记录，不作为当前救治依据"))
        assertTrue(hospital.displayDetails.contains("「$note」"))
    }

    @Test
    fun unknownStatusCannotPromoteFreeTextToAnAvailabilityConclusion() {
        val note = "测试用未分类备注，不应显示"
        val hospital = loaded(fixture().put("antivenomNote", note)).hospitals.single()
        assertFalse(hospital.displayDetails.contains(note))
        assertTrue(hospital.displayDetails.contains("血清信息：未知"))
    }

    @Test
    fun notesRejectControlCharactersAndNonTextButAllowOmissionAndNull() {
        listOf("备注\n伪造新行", "备注\u202E", "备注\u0000", JSONObject(), "x".repeat(1001)).forEach {
            assertRejected(fixture().put("antivenomInfoStatus", "historical_mention").put("antivenomNote", it))
        }
        assertNull(loaded(fixture().put("antivenomNote", JSONObject.NULL)).hospitals.single().antivenomNote)
    }

    @Test
    fun invalidEntriesAreHiddenAndCountedWithoutDiscardingValidEntries() {
        val result = loaded(fixture(), fixture().put("hospitalId", "invalid").apply { remove("source") })
        assertEquals(listOf("fictional-test-only"), result.hospitals.map { it.hospitalId })
        assertEquals(1, result.rejectedEntries)
        val wrongShape = HospitalDirectory.parse("{\"hospitals\":[null,42,\"not an entry\"]}", today)
            as HospitalDirectoryResult.Loaded
        assertTrue(wrongShape.hospitals.isEmpty())
        assertEquals(3, wrongShape.rejectedEntries)
    }

    @Test
    fun duplicateIdsAreNotShownTwice() {
        val result = loaded(fixture(), fixture())
        assertEquals(1, result.hospitals.size)
        assertEquals(1, result.rejectedEntries)
    }

    @Test
    fun extraStockAndDistanceFieldsNeverReachDisplayData() {
        val hospital = loaded(fixture().put("stock", "TEST_STOCK_CLAIM")
            .put("distance", "TEST_DISTANCE_CLAIM")).hospitals.single()
        assertFalse(hospital.displayDetails.contains("TEST_STOCK_CLAIM"))
        assertFalse(hospital.displayDetails.contains("TEST_DISTANCE_CLAIM"))
    }

    @Test
    fun malformedOrWrongShapeDirectoryIsFailureNotAnEmptySuccess() {
        listOf("", "{", "[]", "null", "{}", "{\"hospitals\":null}", "{\"hospitals\":{}}",
            "{\"hospitals\":[", "{\"hospitals\":[]} trailing").forEach { json ->
            assertEquals(HospitalDirectoryResult.Failure(HospitalDirectoryResult.Reason.FORMAT),
                HospitalDirectory.parse(json, today))
        }
    }

    @Test
    fun readFailuresAreVisibleFailureStatesAndParsingStillRunsAfterSuccessfulRead() {
        assertEquals(HospitalDirectoryResult.Failure(HospitalDirectoryResult.Reason.READ),
            HospitalDirectory.load({ throw IOException("fixture missing") }, today))
        assertEquals(HospitalDirectoryResult.Failure(HospitalDirectoryResult.Reason.READ),
            HospitalDirectory.load({ throw SecurityException("fixture denied") }, today))
        assertEquals(HospitalDirectoryResult.Failure(HospitalDirectoryResult.Reason.FORMAT),
            HospitalDirectory.load({ "{" }, today))
        assertEquals(HospitalDirectoryResult.Loaded(emptyList()),
            HospitalDirectory.load({ "{\"hospitals\":[]}" }, today))
    }

    private fun loaded(vararg hospitals: JSONObject): HospitalDirectoryResult.Loaded {
        val array = JSONArray().apply { hospitals.forEach { put(it) } }
        return HospitalDirectory.parse(JSONObject().put("hospitals", array).toString(), today)
            as HospitalDirectoryResult.Loaded
    }

    private fun assertRejected(hospital: JSONObject) {
        val result = loaded(hospital)
        assertTrue("Invalid fixture was displayed", result.hospitals.isEmpty())
        assertEquals(1, result.rejectedEntries)
    }

    // All hospital fixtures are deliberately fictional and live only in test sources.
    private fun fixture() = JSONObject()
        .put("hospitalId", "fictional-test-only")
        .put("name", "测试虚构医院（非真实医疗机构）")
        .put("address", "测试虚构地址（请勿前往）")
        .put("phone", "+1 (202) 555-0100")
        .put("source", "https://hospital-fixture.invalid/source")
        .put("checkedAt", "2024-02-29")
        .put("antivenomInfoStatus", "unknown")
}
