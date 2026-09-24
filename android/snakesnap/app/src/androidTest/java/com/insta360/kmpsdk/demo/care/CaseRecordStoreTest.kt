package com.insta360.kmpsdk.demo.care

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.recognition.RecognitionImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CaseRecordStoreTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var context: ContextWrapper
    private lateinit var store: CaseRecordStore
    private val records: File get() = File(root, CaseRecordStore.DIRECTORY_NAME)

    @Before
    fun setUp() {
        root = File(target.noBackupFilesDir, "case-test-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = root
        }
        store = CaseRecordStore(context)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun noImageNoRecognitionAndEmptyFieldsReopenFromDisk() = runBlocking {
        val saved = store.save(CaseDraft(biteStatus = BiteStatus.BITTEN), null)
        val reopened = CaseRecordStore(context).loadLatest()
        assertEquals(saved, reopened)
        assertEquals(CaseOriginalStatus.NOT_PROVIDED, reopened!!.originalStatus)
        assertTrue(reopened.cardText().contains("症状：未提供"))
        assertNull(store.originalFile(reopened))
    }

    @Test
    fun originalBytesAreUnchangedAndOnlyPrivateNoBackupFilesAreUsed() = runBlocking {
        val source = syntheticJpeg()
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val originalBytes = source.readBytes()
        val saved = store.save(
            CaseDraft(biteStatus = BiteStatus.NOT_BITTEN, importedAt = "2026-09-01T00:00:00Z", recognitionSummary = "【MOCK】uncertain"),
            Uri.fromFile(source),
        )
        val original = store.originalFile(saved)!!
        assertEquals(CaseOriginalStatus.SAVED, saved.originalStatus)
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(originalBytes.size.toLong(), saved.originalBytes)
        val backupExcludedRoot = target.noBackupFilesDir.canonicalPath + File.separator
        listOf(original, File(records, "${saved.id}.json"), File(records, CaseRecordStore.LATEST_FILE)).forEach {
            assertTrue(it.isFile)
            assertTrue(it.canonicalPath.startsWith(backupExcludedRoot))
            assertFalse(it.canonicalPath.startsWith(target.filesDir.canonicalPath + File.separator))
            assertFalse(it.canonicalPath.startsWith(target.cacheDir.canonicalPath + File.separator))
        }
        val json = JSONObject(File(records, "${saved.id}.json").readText())
        assertFalse(json.has("originalImageUri"))
        source.delete()
        val reopened = CaseRecordStore(context).loadLatest()!!
        val preview = RecognitionImage.read(context.contentResolver, Uri.fromFile(store.originalFile(reopened)!!))
        assertEquals(40, preview.width)
        assertEquals(80, preview.height)
        assertTrue(preview.jpeg.size <= 2_000_000)
    }

    @Test
    fun oversizedOriginalRequiresExplicitContinuationAndLeavesNoPartialImage() = runBlocking {
        val source = File(root, "synthetic-large.bin")
        RandomAccessFile(source, "rw").use { it.setLength(CaseRecordStore.MAX_ORIGINAL_BYTES + 1) }
        val draft = CaseDraft(biteStatus = BiteStatus.BITTEN, recognitionSummary = "【MOCK · 识别失败】\nUPSTREAM_TIMEOUT")
        try {
            store.save(draft, Uri.fromFile(source))
            fail("Oversized original must not silently save a text-only card")
        } catch (failure: CaseOriginalUnavailable) {
            assertTrue(failure.userMessage.contains("32 MiB"))
        }
        assertNull(store.loadLatest())
        assertFalse(File(records, "${draft.id}.json").exists())
        assertFalse(File(records, "${draft.id}.original").exists())
        assertTrue(records.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        val saved = store.save(draft, Uri.fromFile(source), continueWithoutOriginal = true)
        assertEquals(CaseOriginalStatus.OMITTED_AFTER_FAILURE, saved.originalStatus)
        assertTrue(saved.cardText().contains("UPSTREAM_TIMEOUT"))
        assertEquals(saved, CaseRecordStore(context).loadLatest())
    }

    @Test
    fun inaccessibleOriginalCanBeOmittedWithoutLosingInjuryFields() = runBlocking {
        val draft = CaseDraft(biteStatus = BiteStatus.BITTEN, details = CaseDetails(bodyPart = "合成测试：左脚", symptoms = "合成测试症状"))
        val missing = Uri.fromFile(File(root, "missing.jpg"))
        try {
            store.save(draft, missing)
            fail("Missing original must be reported")
        } catch (_: CaseOriginalUnavailable) {
            assertNull(store.loadLatest())
        }
        val record = store.save(draft, missing, continueWithoutOriginal = true)
        assertEquals(draft.details, record.draft.details)
        assertEquals(CaseOriginalStatus.OMITTED_AFTER_FAILURE, record.originalStatus)
    }

    @Test
    fun repeatedSaveIsIdempotentAndPointerFailureCanBeRetried() = runBlocking {
        assertTrue(records.mkdirs())
        val pointer = File(records, CaseRecordStore.LATEST_FILE)
        assertTrue(pointer.mkdir())
        val draft = CaseDraft(biteStatus = BiteStatus.BITTEN, recognitionSummary = "pending")
        try {
            store.save(draft, null)
            fail("Replacing a directory with a pointer must fail visibly")
        } catch (failure: CaseLatestPointerUnavailable) {
            assertTrue(failure.cause is IOException)
            assertEquals(draft, failure.record.draft)
            assertEquals(failure.record, store.load(draft.id))
        }
        val committed = store.load(draft.id)!!
        try {
            store.save(draft.copy(recognitionSummary = "changed"), null)
            fail("Retrying an unavailable pointer must still report the committed card")
        } catch (failure: CaseLatestPointerUnavailable) {
            assertEquals(committed, failure.record)
        }
        assertTrue(pointer.delete())
        val saved = store.save(draft, null)
        assertEquals(committed, saved)
        val repeated = CaseRecordStore(context).save(draft.copy(recognitionSummary = "changed"), null)
        assertEquals(saved, repeated)
        assertEquals("pending", repeated.draft.recognitionSummary)
        assertEquals(saved, CaseRecordStore(context).loadLatest())
        assertEquals(1, records.listFiles().orEmpty().count { it.name.endsWith(".json") && it.name != CaseRecordStore.LATEST_FILE })
        assertTrue(records.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun partialCommitLocksFieldsSurvivesRestorationAndRetriesOnlyTheLatestPointer() = runBlocking {
        assertTrue(records.mkdirs())
        val pointer = File(records, CaseRecordStore.LATEST_FILE)
        assertTrue(pointer.mkdir())
        val source = syntheticJpeg()
        val originalBytes = source.readBytes()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launch = CaseRecordActivity.intent(
            context, Uri.fromFile(source), null, "【MOCK】pending", arrayListOf("候选甲"), BiteStatus.BITTEN,
        )
        val pendingMessage = "信息卡已保存，但最近记录入口更新失败；可重试索引"
        val savedState = Bundle()
        val owner = ViewModelStore()
        lateinit var model: CaseRecordViewModel
        try {
            instrumentation.runOnMainSync {
                model = CaseRecordViewModel(context, launch, null)
                owner.put("case", model)
            }
            withTimeout(5000) { model.state.first { !it.busy } }
            val draftId = model.state.value.draft.id
            instrumentation.runOnMainSync {
                model.edit { copy(bodyPart = "合成测试：左脚", symptoms = "  合成测试症状  ") }
                model.compare(1)
                model.save()
            }
            val partial = withTimeout(5000) { model.state.first { !it.busy } }
            assertTrue(partial.pendingLatest)
            assertNotNull(partial.record)
            assertFalse(partial.allowWithoutOriginal)
            assertEquals(pendingMessage, partial.message)
            val saved = partial.record!!
            assertEquals(draftId, saved.id)
            assertEquals("合成测试症状", saved.draft.details.symptoms)
            assertEquals(1, saved.draft.comparisonIndex)
            assertEquals(saved.draft, partial.draft)
            assertEquals(saved, store.load(draftId))
            assertEquals(CaseOriginalStatus.SAVED, saved.originalStatus)
            assertArrayEquals(originalBytes, store.originalFile(saved)!!.readBytes())
            val recordFile = File(records, "$draftId.json")
            val recordBytes = recordFile.readBytes()
            assertTrue(pointer.isDirectory)
            // A retry must use the committed original, even after the imported source disappears.
            assertTrue(source.delete())
            instrumentation.runOnMainSync {
                model.edit { copy(symptoms = "不能接受的修改") }
                model.compare(0)
                assertEquals(saved.draft, model.state.value.draft)
                model.saveState(savedState)
                owner.clear()
                model = CaseRecordViewModel(context, launch, savedState)
                owner.put("case", model)
            }
            val restored = withTimeout(5000) { model.state.first { !it.busy } }
            assertTrue(restored.pendingLatest)
            assertEquals(saved, restored.record)
            assertEquals(saved.draft, restored.draft)
            assertEquals(pendingMessage, restored.message)
            instrumentation.runOnMainSync {
                model.edit { copy(symptoms = "重建后也不能接受的修改") }
                model.compare(0)
                assertEquals(saved.draft, model.state.value.draft)
                model.save()
                assertTrue(model.state.value.busy)
            }
            val stillPending = withTimeout(5000) { model.state.first { !it.busy } }
            assertTrue(stillPending.pendingLatest)
            assertEquals(saved, stillPending.record)
            assertEquals(saved.draft, stillPending.draft)
            assertEquals(pendingMessage, stillPending.message)
            assertTrue(pointer.delete())
            instrumentation.runOnMainSync { model.save() }
            val completed = withTimeout(5000) { model.state.first { !it.busy } }
            assertFalse(completed.pendingLatest)
            assertEquals(saved, completed.record)
            assertEquals(saved.draft, completed.draft)
            assertEquals("信息卡已保存在本机；不是诊断。", completed.message)
            assertTrue(pointer.isFile)
            assertEquals(saved.id, JSONObject(pointer.readText()).getString("id"))
            assertEquals(saved, CaseRecordStore(context).loadLatest())
            assertArrayEquals(recordBytes, recordFile.readBytes())
            assertArrayEquals(originalBytes, store.originalFile(saved)!!.readBytes())
            assertEquals(1, records.listFiles().orEmpty().count { it.name.endsWith(".json") && it.name != CaseRecordStore.LATEST_FILE })
            assertEquals(1, records.listFiles().orEmpty().count { it.name.endsWith(".original") })
            assertTrue(records.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            instrumentation.runOnMainSync {
                model.edit { copy(symptoms = "保存成功后仍不能修改") }
                model.save()
                assertFalse(model.state.value.busy)
                assertEquals(saved.draft, model.state.value.draft)
                model.saveState(savedState)
                owner.clear()
                model = CaseRecordViewModel(context, launch, savedState)
                owner.put("case", model)
            }
            val reopened = withTimeout(5000) { model.state.first { !it.busy } }
            assertFalse(reopened.pendingLatest)
            assertEquals(saved, reopened.record)
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
        }
    }

    @Test
    fun missingPreviouslySavedOriginalIsNotReportedAsAvailable() = runBlocking {
        val saved = store.save(CaseDraft(biteStatus = BiteStatus.NOT_BITTEN), Uri.fromFile(syntheticJpeg()))
        assertTrue(store.originalFile(saved)!!.delete())
        val reopened = CaseRecordStore(context).loadLatest()!!
        assertEquals(saved, reopened)
        assertNull(store.originalFile(reopened))
    }

    @Test
    fun restoredFormSavesOnceAndLatestEntryUsesDiskRatherThanOldUiState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launch = CaseRecordActivity.intent(context, null, null, "【MOCK】pending", arrayListOf("候选甲"), BiteStatus.BITTEN)
        val savedState = Bundle()
        val owner = ViewModelStore()
        lateinit var model: CaseRecordViewModel
        try {
            instrumentation.runOnMainSync {
                model = CaseRecordViewModel(context, launch, null)
                owner.put("case", model)
            }
            withTimeout(5000) { model.state.first { !it.busy } }
            instrumentation.runOnMainSync {
                model.edit { copy(biteTime = "合成测试时间", symptoms = "合成测试症状") }
                model.compare(1)
                model.saveState(savedState)
                owner.clear()
                model = CaseRecordViewModel(context, launch, savedState)
                owner.put("case", model)
            }
            withTimeout(5000) { model.state.first { !it.busy } }
            assertEquals("合成测试症状", model.state.value.draft.details.symptoms)
            assertEquals(1, model.state.value.draft.comparisonIndex)
            instrumentation.runOnMainSync {
                model.save()
                model.save()
            }
            val saved = withTimeout(5000) { model.state.first { it.record != null } }.record!!
            instrumentation.runOnMainSync {
                owner.clear()
                model = CaseRecordViewModel(context, CaseRecordActivity.latestIntent(context), null)
                owner.put("case", model)
            }
            val reopened = withTimeout(5000) { model.state.first { !it.busy } }.record!!
            assertEquals(saved, reopened)
            assertEquals("【MOCK】pending", reopened.draft.recognitionSummary)
            assertEquals(1, records.listFiles().orEmpty().count { it.name.endsWith(".json") && it.name != CaseRecordStore.LATEST_FILE })
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
        }
    }

    private fun syntheticJpeg(): File {
        val file = File(root, "synthetic.jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
