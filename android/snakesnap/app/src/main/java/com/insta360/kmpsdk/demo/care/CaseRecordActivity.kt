package com.insta360.kmpsdk.demo.care

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.insta360.kmpsdk.demo.databinding.ActivityCaseRecordBinding
import com.insta360.kmpsdk.demo.recognition.RecognitionImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CaseRecordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCaseRecordBinding
    private lateinit var model: CaseRecordViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCaseRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val appContext = applicationContext
        val launchIntent = intent
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CaseRecordViewModel(appContext, launchIntent, savedInstanceState) as T
        })[CaseRecordViewModel::class.java]

        binding.caseBack.setOnClickListener { finish() }
        binding.caseHospitals.setOnClickListener {
            try {
                startActivity(Intent().setClassName(this, "com.insta360.kmpsdk.demo.hospital.HospitalDirectoryActivity"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "医院目录暂未接入；急需救治请拨打120。", Toast.LENGTH_LONG).show()
            }
        }
        bindForm()
        binding.caseSave.setOnClickListener {
            if (model.latestOnly) model.reload() else model.save()
        }
        binding.caseSaveWithoutOriginal.setOnClickListener { model.save(withoutOriginal = true) }
        render(model.state.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
    }

    private fun bindForm() {
        val draft = model.state.value.draft
        binding.caseBiteTime.setText(draft.details.biteTime)
        binding.caseBodyPart.setText(draft.details.bodyPart)
        binding.caseSymptoms.setText(draft.details.symptoms)
        binding.caseAge.setText(draft.details.age)
        binding.caseBloodType.setText(draft.details.bloodType)
        binding.caseEmergencyName.setText(draft.details.emergencyName)
        binding.caseEmergencyPhone.setText(draft.details.emergencyPhone)
        binding.caseEmergencyRelationship.setText(draft.details.emergencyRelationship)
        binding.caseLocation.setText(draft.details.location)
        binding.caseBiteTime.doAfterTextChanged { model.edit { copy(biteTime = it.toString()) } }
        binding.caseBodyPart.doAfterTextChanged { model.edit { copy(bodyPart = it.toString()) } }
        binding.caseSymptoms.doAfterTextChanged { model.edit { copy(symptoms = it.toString()) } }
        binding.caseAge.doAfterTextChanged { model.edit { copy(age = it.toString()) } }
        binding.caseBloodType.doAfterTextChanged { model.edit { copy(bloodType = it.toString()) } }
        binding.caseEmergencyName.doAfterTextChanged { model.edit { copy(emergencyName = it.toString()) } }
        binding.caseEmergencyPhone.doAfterTextChanged { model.edit { copy(emergencyPhone = it.toString()) } }
        binding.caseEmergencyRelationship.doAfterTextChanged { model.edit { copy(emergencyRelationship = it.toString()) } }
        binding.caseLocation.doAfterTextChanged { model.edit { copy(location = it.toString()) } }
        val choices = listOf("未比对") + draft.candidateLabels.map { "用户比对选择：$it" }
        binding.caseCandidateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, choices).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.caseCandidateSpinner.setSelection(draft.comparisonIndex)
        binding.caseCandidateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                model.compare(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val snapshot = draft.recognitionSummary.ifBlank { "未提供" }
        val importedAt = draft.importedAt?.takeIf { it.isNotBlank() } ?: "未提供"
        binding.caseRecognitionSnapshot.text = "图片导入时间：$importedAt\n\n识别快照（原样保留来源与状态，非诊断）：\n$snapshot"
    }

    private fun render(state: CaseRecordUiState) {
        val hasRecord = state.record != null
        binding.caseBranchMessage.text = when {
            model.latestOnly && !hasRecord -> "查看最近保存的本地信息卡。"
            state.draft.bitten -> "被咬（用户填写）。请立即就医，不要为填写或保存信息卡延误救治。"
            else -> "未被咬（用户填写）。请保持距离，不要触碰或捕捉；未被咬、识别失败或未检出均不等于安全。"
        }
        binding.caseStatus.text = state.message
        binding.caseStatus.isVisible = state.message.isNotEmpty()
        binding.caseProgress.isVisible = state.busy
        binding.caseForm.isVisible = !hasRecord && !state.pendingLatest && !model.latestOnly
        binding.caseBiteFields.isVisible = state.draft.bitten
        listOf(
            binding.caseBiteTime, binding.caseBodyPart, binding.caseSymptoms,
            binding.caseAge, binding.caseBloodType, binding.caseEmergencyName,
            binding.caseEmergencyPhone, binding.caseEmergencyRelationship,
            binding.caseLocation, binding.caseCandidateSpinner,
        ).forEach { it.isEnabled = !state.busy && !hasRecord && !state.pendingLatest }
        binding.caseSave.isVisible = !hasRecord || state.pendingLatest
        binding.caseSave.isEnabled = !state.busy
        binding.caseSave.text = when {
            state.pendingLatest -> "重试更新最近记录"
            model.latestOnly -> "重新读取最近记录"
            else -> "仅在本机保存信息卡"
        }
        binding.caseSaveWithoutOriginal.isVisible = !hasRecord && !state.pendingLatest && state.allowWithoutOriginal && !model.latestOnly
        binding.caseSaveWithoutOriginal.isEnabled = !state.busy
        binding.caseSavedCard.isVisible = hasRecord
        binding.caseSavedCard.text = state.record?.cardText().orEmpty()
        binding.caseImageStatus.text = state.imageMessage
        binding.caseImagePreview.isVisible = state.preview != null
        binding.caseImagePreview.setImageBitmap(state.preview)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        model.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    companion object {
        internal const val EXTRA_URI = "care.originalImageUri"
        internal const val EXTRA_IMPORTED_AT = "care.importedAt"
        internal const val EXTRA_SUMMARY = "care.recognitionSummary"
        internal const val EXTRA_LABELS = "care.candidateLabels"
        internal const val EXTRA_BITTEN = "care.bitten"
        internal const val EXTRA_LATEST = "care.latest"

        fun intent(
            context: Context,
            originalImageUri: Uri?,
            importedAt: String?,
            recognitionSummary: String,
            candidateLabels: ArrayList<String>,
            bitten: Boolean,
        ): Intent = Intent(context, CaseRecordActivity::class.java)
            .putExtra(EXTRA_URI, originalImageUri)
            .putExtra(EXTRA_IMPORTED_AT, importedAt)
            .putExtra(EXTRA_SUMMARY, recognitionSummary)
            .putStringArrayListExtra(EXTRA_LABELS, ArrayList(candidateLabels.take(3)))
            .putExtra(EXTRA_BITTEN, bitten)

        fun latestIntent(context: Context): Intent = Intent(context, CaseRecordActivity::class.java)
            .putExtra(EXTRA_LATEST, true)
    }
}

internal data class CaseRecordUiState(
    val draft: CaseDraft,
    val busy: Boolean = true,
    val record: CaseRecord? = null,
    val pendingLatest: Boolean = false,
    val message: String = "",
    val allowWithoutOriginal: Boolean = false,
    val preview: Bitmap? = null,
    val imageMessage: String = "",
)

internal class CaseRecordViewModel(
    context: Context,
    intent: Intent,
    restored: Bundle?,
) : ViewModel() {
    private val store = CaseRecordStore(context)
    private val resolver = context.contentResolver
    val latestOnly = intent.getBooleanExtra(CaseRecordActivity.EXTRA_LATEST, false)
    @Suppress("DEPRECATION")
    private val originalUri = intent.getParcelableExtra<Uri>(CaseRecordActivity.EXTRA_URI)
    private val viewingId = restored?.getString(STATE_VIEWING_ID)
    private var previewJob: Job? = null
    private val mutableState = MutableStateFlow(
        CaseRecordUiState(
            draft = restoreDraft(intent, restored),
            pendingLatest = restored?.getBoolean(STATE_PENDING_LATEST) ?: false,
            message = restored?.getString(STATE_MESSAGE).orEmpty(),
            allowWithoutOriginal = restored?.getBoolean(STATE_WITHOUT_ORIGINAL) ?: false,
        ),
    )
    val state = mutableState.asStateFlow()

    init {
        loadInitial()
    }

    fun edit(change: CaseDetails.() -> CaseDetails) {
        val current = state.value
        if (!current.busy && current.record == null && !current.pendingLatest) {
            mutableState.value = current.copy(draft = current.draft.copy(details = change(current.draft.details)))
        }
    }

    fun compare(index: Int) {
        val current = state.value
        if (!current.busy && current.record == null && !current.pendingLatest && index in 0..current.draft.candidateLabels.size) {
            mutableState.value = current.copy(draft = current.draft.copy(comparisonIndex = index))
        }
    }

    fun reload() {
        if (!state.value.busy) loadInitial()
    }

    private fun loadInitial() {
        mutableState.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val record = if (latestOnly) {
                    if (viewingId != null) store.load(viewingId) else store.loadLatest()
                } else store.load(state.value.draft.id)
                mutableState.value = state.value.copy(
                    busy = false,
                    record = record,
                    draft = record?.draft ?: state.value.draft,
                    message = when {
                        record != null && state.value.pendingLatest -> PENDING_LATEST_MESSAGE
                        record != null -> "已读取本地保存的信息卡；不是诊断。"
                        latestOnly -> "暂无本地信息卡。可返回后选择被咬／未被咬，再创建记录。"
                        else -> state.value.message
                    },
                )
                preparePreview(record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(busy = false, message = "本地记录读取失败，表单仍保留。请重试；不要因此延误就医。")
            }
        }
    }

    fun save(withoutOriginal: Boolean = false) {
        val current = state.value
        if (current.busy || (current.record != null && !current.pendingLatest) || latestOnly) return
        if (withoutOriginal && !current.allowWithoutOriginal) return
        mutableState.value = current.copy(
            busy = true,
            message = if (current.pendingLatest) "正在更新最近记录入口；信息卡已保存。"
            else "正在保存到本机；返回和医院入口仍可使用。",
        )
        viewModelScope.launch {
            try {
                val record = store.save(current.draft, originalUri, withoutOriginal)
                mutableState.value = state.value.copy(
                    draft = record.draft, record = record, busy = false, pendingLatest = false,
                    allowWithoutOriginal = false, message = "信息卡已保存在本机；不是诊断。",
                )
                preparePreview(record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CaseLatestPointerUnavailable) {
                mutableState.value = state.value.copy(
                    draft = failure.record.draft, record = failure.record,
                    busy = false, pendingLatest = true, allowWithoutOriginal = false,
                    message = PENDING_LATEST_MESSAGE,
                )
                preparePreview(failure.record)
            } catch (failure: CaseOriginalUnavailable) {
                mutableState.value = state.value.copy(
                    busy = false, allowWithoutOriginal = true,
                    message = "${failure.userMessage}\n信息卡尚未保存。可重试，或点击“不保存原图，继续记录”。",
                )
            } catch (_: Exception) {
                mutableState.value = state.value.copy(
                    busy = false,
                    message = if (current.pendingLatest) PENDING_LATEST_MESSAGE
                    else "本地保存尚未完成。表单仍保留，请重试；不会重复创建本条。",
                )
            }
        }
    }

    private fun preparePreview(record: CaseRecord?) {
        previewJob?.cancel()
        mutableState.value = state.value.copy(preview = null, imageMessage = "正在检查原图与生成预览…")
        previewJob = viewModelScope.launch {
            val (bitmap, message) = withContext(Dispatchers.IO) { readPreview(record) }
            mutableState.value = state.value.copy(preview = bitmap, imageMessage = message)
        }
    }

    private suspend fun readPreview(record: CaseRecord?): Pair<Bitmap?, String> {
        var savedOriginal = false
        return try {
            val uri: Uri
            val prefix: String
            when (record?.originalStatus) {
                CaseOriginalStatus.NOT_PROVIDED -> return null to "未提供原图；本卡仅保存文字记录。"
                CaseOriginalStatus.OMITTED_AFTER_FAILURE -> return null to "原图保存失败后，用户选择不附原图继续。未保存原图，本卡仅保存文字记录。"
                CaseOriginalStatus.SAVED -> {
                    val file = store.originalFile(record)
                        ?: return null to "已记录的原图现已缺失或不完整；当前没有可用原图，文字信息卡仍可查看。"
                    savedOriginal = true
                    uri = Uri.fromFile(file)
                    prefix = "原图已按原字节保存在本机私有不备份目录（${record.originalBytes} 字节）。"
                }
                null -> {
                    uri = originalUri ?: return null to "未提供原图；无需图片也能记录。"
                    require(uri.scheme == "content" || uri.scheme == "file")
                    prefix = "导入图片尚未保存；保存时按原字节复制，最多 32 MiB。"
                }
            }
            val prepared = RecognitionImage.read(resolver, uri)
            val bitmap = BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.size)
                ?: return null to "$prefix\n无法生成预览。"
            bitmap to "$prefix\n下方是降采样、方向校正并重新编码的预览，不是原图，不作为诊断依据。"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null to if (savedOriginal) "原图文件已保存在私有不备份目录，但无法生成预览。"
            else "预览读取失败，原图尚未保存。仍可尝试保存；原图保存失败时可选择不附原图继续。"
        }
    }

    fun saveState(out: Bundle) {
        val current = state.value
        out.putString(STATE_ID, current.draft.id)
        out.putString(STATE_DETAILS, current.draft.details.toJson().toString())
        out.putInt(STATE_COMPARISON, current.draft.comparisonIndex)
        out.putString(STATE_VIEWING_ID, current.record?.id ?: viewingId)
        out.putBoolean(STATE_WITHOUT_ORIGINAL, current.allowWithoutOriginal)
        out.putBoolean(STATE_PENDING_LATEST, current.pendingLatest)
        out.putString(STATE_MESSAGE, when {
            current.pendingLatest -> PENDING_LATEST_MESSAGE
            current.busy -> "上次操作未完成，表单已保留；请重试。"
            else -> current.message
        })
    }

    companion object {
        private const val STATE_ID = "care.state.id"
        private const val STATE_DETAILS = "care.state.details"
        private const val STATE_COMPARISON = "care.state.comparison"
        private const val STATE_VIEWING_ID = "care.state.viewingId"
        private const val STATE_WITHOUT_ORIGINAL = "care.state.withoutOriginal"
        private const val STATE_PENDING_LATEST = "care.state.pendingLatest"
        private const val STATE_MESSAGE = "care.state.message"
        private const val PENDING_LATEST_MESSAGE = "信息卡已保存，但最近记录入口更新失败；可重试索引"

        private fun restoreDraft(intent: Intent, restored: Bundle?): CaseDraft {
            val base = CaseDraft(
                bitten = intent.getBooleanExtra(CaseRecordActivity.EXTRA_BITTEN, false),
                importedAt = intent.getStringExtra(CaseRecordActivity.EXTRA_IMPORTED_AT),
                recognitionSummary = intent.getStringExtra(CaseRecordActivity.EXTRA_SUMMARY).orEmpty(),
                candidateLabels = intent.getStringArrayListExtra(CaseRecordActivity.EXTRA_LABELS).orEmpty().take(3),
            )
            return base.copy(
                id = restored?.getString(STATE_ID) ?: base.id,
                details = restored?.getString(STATE_DETAILS)?.let { CaseDetails.fromJson(JSONObject(it)) } ?: base.details,
                comparisonIndex = (restored?.getInt(STATE_COMPARISON) ?: 0).coerceIn(0, base.candidateLabels.size),
            )
        }
    }
}
