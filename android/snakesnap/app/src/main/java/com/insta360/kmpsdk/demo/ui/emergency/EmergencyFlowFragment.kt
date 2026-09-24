package com.insta360.kmpsdk.demo.ui.emergency

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.arashivision.sdk.camera.core.model.FunctionMode
import com.arashivision.sdk.camera.core.model.FunctionType
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.care.BiteStatus
import com.insta360.kmpsdk.demo.care.CaseRecordActivity
import com.insta360.kmpsdk.demo.databinding.FragmentEmergencyFlowBinding
import com.insta360.kmpsdk.demo.hospital.HospitalDirectoryActivity
import com.insta360.kmpsdk.demo.species.SpeciesComparisonActivity
import com.insta360.kmpsdk.demo.ui.capture.CameraCaptureViewModel
import com.insta360.kmpsdk.demo.ui.capture.CaptureUiEffect
import com.insta360.kmpsdk.demo.ui.common.DemoTopConnectionStatusBinder
import com.insta360.kmpsdk.demo.ui.connection.ConnectState
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import kotlinx.coroutines.launch

/**
 * 紧急一键流程主线页：拍照 → 下载到手机 → 识别（真实 / MOCK 降级）→ 病例卡 / 医院求助。
 *
 * 拍摄控制完全复用 [CameraCaptureViewModel]（含防重复点击遮罩、强制关闭自拍倒计时），
 * 本页只负责编排「拍完之后」的链路与展示。
 *
 * 注意：这里**刻意不**调用 `observeCameraDisconnectedNavigateToConnection`。
 * 相机中途断连时若自动弹回连接页，会把已经摆在用户面前的求助出口一起带走，
 * 违反「任何一步失败都不能阻断求助路径」的产品红线。断连只禁用拍摄按钮。
 */
class EmergencyFlowFragment : Fragment() {

    private var _binding: FragmentEmergencyFlowBinding? = null
    private val binding get() = _binding!!

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    private val captureViewModel: CameraCaptureViewModel by viewModels {
        CameraCaptureViewModel.Factory(requireActivity().application) {
            connectionViewModel.getCameraDevice()
        }
    }

    private val flowViewModel: EmergencyFlowViewModel by viewModels {
        EmergencyFlowViewModel.Factory(requireActivity().application)
    }

    /** 只在进入页面后纠正一次拍摄模式，避免反复打断用户手动选择。 */
    private var photoModeEnsured = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEmergencyFlowBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 本页现在既是 App 首页（底部导航一等 Tab），也是从连接页跳进来的子页。
        // 作为首页时没有上一级，返回箭头一点就直接退出 App —— 藏掉它，避免误退。
        binding.backLink.isVisible = findNavController().previousBackStackEntry != null
        binding.backLink.setOnClickListener { findNavController().popBackStack() }
        // 未连接时「去连接」的出口：本页是首页，没有底部导航以外的路径能到连接页，
        // 让提示本身就是按钮，别让用户对着提示猜怎么走。
        binding.connectHint.setOnClickListener {
            findNavController().navigate(R.id.connectionFragment)
        }
        binding.captureButton.setOnClickListener { captureViewModel.onPrimaryCaptureButtonClicked() }

        // 上传同意：默认不勾选（布局 checked=false + saveEnabled=false），改选即同步 VM。
        // 真实模式下未勾选点拍照，HttpRecognitionAdapter 会在本地失败 UPLOAD_CONSENT_REQUIRED，不发任何请求。
        binding.uploadConsent.setOnCheckedChangeListener { _, checked ->
            flowViewModel.onUploadConsentChanged(checked)
        }
        // pending 时唯一的人工查询入口；没有任何定时器/轮询调用 onRefreshPendingClicked。
        binding.pendingRefreshButton.setOnClickListener { flowViewModel.onRefreshPendingClicked() }

        // 求助出口：常驻常亮，与链路成败无关（红线：失败不得阻断求助路径）。
        binding.helpTitle.isVisible = true
        binding.helpDisclaimer.isVisible = true
        listOf(binding.helpBitten, binding.helpNotBitten, binding.helpHospital).forEach {
            it.isVisible = true
            it.isEnabled = true
        }
        binding.helpBitten.setOnClickListener { flowViewModel.onHelpClicked(BiteStatus.BITTEN) }
        binding.helpNotBitten.setOnClickListener { flowViewModel.onHelpClicked(BiteStatus.NOT_BITTEN) }
        binding.helpHospital.setOnClickListener { flowViewModel.onHospitalClicked() }

        observeConnection()
        observeCapture()
        observeFlow()

        // 页面存活期间保持 capture 监听器注册：
        // 相机机身按键 / 快捷键触发的拍照同样会回调 onCaptureFinish，走完全相同的后续链路。
        captureViewModel.onAppear()
    }

    private fun observeConnection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectionViewModel.connectionUi.collect { conn ->
                    DemoTopConnectionStatusBinder.bind(
                        requireContext(),
                        binding.layoutConnStatus.connectionStatusDot,
                        binding.layoutConnStatus.connectionStatusText,
                        conn,
                    )
                    val connected = conn.connectState == ConnectState.Connected
                    binding.connectHint.isVisible = !connected
                    flowViewModel.onConnected(connected)
                    renderCaptureAvailability()
                }
            }
        }
    }

    private fun observeCapture() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureViewModel.ui.collect { s ->
                    ensurePhotoMode(s.modeOptions, s.selectedMode)
                    flowViewModel.onCaptureAvailability(
                        s.captureButtonEnabled && s.selectedMode != null,
                    )
                    // busy（拉配置）与拍摄命令在途共用同一全屏遮罩，沿用 CaptureFragment 语义。
                    val blocking = s.busy || s.captureCommand != null
                    binding.blockingOverlay.isVisible = blocking
                    if (blocking) binding.blockingOverlay.setText(s.loadingText.orEmpty())
                    renderCaptureAvailability()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureViewModel.effect.collect { effect ->
                    if (effect is CaptureUiEffect.CaptureFinished) {
                        val isPhoto = effect.functionMode.functionType == FunctionType.PHOTO
                        flowViewModel.onCaptureFinished(isPhoto, effect.filePaths)
                    }
                }
            }
        }
    }

    private fun observeFlow() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    flowViewModel.ui.collect { s ->
                        binding.stagePanel.isVisible = s.stage != EmergencyStage.IDLE
                        binding.stageText.text = s.stageText
                        binding.stageDetail.text = s.detailText
                        binding.stageDetail.isVisible = s.detailText.isNotEmpty()

                        val showProgress = s.progressPercent in 0..100
                        binding.stageProgress.isVisible = showProgress
                        if (showProgress) binding.stageProgress.progress = s.progressPercent

                        renderUploadConsent(s)
                        renderPendingRefresh(s)

                        binding.summaryText.isVisible = s.summaryText.isNotEmpty()
                        binding.summaryText.text = s.summaryText

                        binding.imageWarning.isVisible = s.imageWarningText.isNotEmpty()
                        binding.imageWarning.text = s.imageWarningText

                        renderCandidates(s.candidates)
                        // 策略层恒返回 true，这里照做即可；保留调用是为了让红线可测、不可回退。
                        listOf(binding.helpBitten, binding.helpNotBitten, binding.helpHospital)
                            .forEach { it.isEnabled = s.helpExitsEnabled }
                        renderCaptureAvailability()
                    }
                }
                launch {
                    flowViewModel.event.collect { event ->
                        when (event) {
                            is EmergencyFlowEvent.OpenCare -> openCare(event.biteStatus, event.auto)
                            EmergencyFlowEvent.OpenHospital ->
                                startActivity(Intent(requireContext(), HospitalDirectoryActivity::class.java))

                            is EmergencyFlowEvent.OpenSpecies -> startActivity(
                                SpeciesComparisonActivity.intent(requireContext(), event.speciesId)
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 一次性上传授权：只在真实识别模式出现；勾选即持久化（一次明示授权、后续零操作），
     * 取消勾选立即撤销。MOCK 降级模式不发网络，无需授权项。
     */
    private fun renderUploadConsent(s: EmergencyFlowUiState) {
        binding.uploadConsent.isVisible = s.liveEnabled
        if (binding.uploadConsent.isChecked != s.consentGranted) {
            binding.uploadConsent.isChecked = s.consentGranted
        }
        binding.uploadConsent.isEnabled = !s.requestInFlight
    }

    /**
     * 「查询一次结果」：仅 pending 且请求不在途时可点；自动查询在途期间禁用（防与自动查询撞车，
     * 契约 L80 禁止重复发送），5 次自动查询用尽后（pendingManualAvailable=true）恢复人工可点。
     */
    private fun renderPendingRefresh(s: EmergencyFlowUiState) {
        val visible = s.pendingRecognitionId != null
        val enabled = visible && !s.requestInFlight && s.pendingManualAvailable
        binding.pendingRefreshButton.isVisible = visible
        binding.pendingRefreshButton.isEnabled = enabled
        binding.pendingRefreshButton.alpha = if (enabled) 1f else 0.5f
    }

    /** 遮罩期与链路进行中（含 pending 查询在途）都不让重复按快门，其余时候按 CameraCaptureViewModel 的结论放行。 */
    private fun renderCaptureAvailability() {
        val flow = flowViewModel.ui.value
        val pipelineRunning = flow.stage == EmergencyStage.FETCHING ||
            flow.stage == EmergencyStage.PREPARING ||
            flow.stage == EmergencyStage.RECOGNIZING
        val enabled = flow.connected && flow.captureEnabled && !flow.requestInFlight &&
            !binding.blockingOverlay.isVisible && !pipelineRunning
        binding.captureButton.isEnabled = enabled
        binding.captureButton.isClickable = enabled
        binding.captureButton.alpha = if (enabled) 1f else 0.5f
    }

    /**
     * 紧急拍照必须真的出「照片」。相机若停在视频模式，按下快门会开始录像，
     * 后续链路只能跳过。这里复用既有的 [CameraCaptureViewModel.selectMode] 纠正一次。
     */
    private fun ensurePhotoMode(options: List<FunctionMode>, selected: FunctionMode?) {
        if (photoModeEnsured || options.isEmpty()) return
        if (selected?.functionType == FunctionType.PHOTO) {
            photoModeEnsured = true
            return
        }
        if (options.contains(FunctionMode.PHOTO_NORMAL)) {
            photoModeEnsured = true
            captureViewModel.selectMode(FunctionMode.PHOTO_NORMAL)
        }
    }

    private fun renderCandidates(candidates: List<EmergencyCandidateUi>) {
        val host = binding.candidateHost
        host.removeAllViews()
        val res = resources
        val padH = res.getDimensionPixelSize(R.dimen.emergency_candidate_padding_h)
        val padV = res.getDimensionPixelSize(R.dimen.emergency_candidate_padding_v)
        val gap = res.getDimensionPixelSize(R.dimen.emergency_candidate_gap)
        val minTarget = res.getDimensionPixelSize(R.dimen.emergency_touch_target_min)
        val suffix = getString(R.string.emergency_flow_candidate_suffix)
        candidates.forEach { candidate ->
            host.addView(
                TextView(requireContext()).apply {
                    text = "${candidate.commonName} $suffix"
                    textSize = 15f
                    setTextColor(res.getColor(R.color.emergency_ink, null))
                    setBackgroundResource(R.drawable.bg_emergency_candidate)
                    setPadding(padH, padV, padH, padV)
                    minHeight = minTarget
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { flowViewModel.onCandidateClicked(candidate) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = gap },
            )
        }
    }

    private fun openCare(biteStatus: BiteStatus, auto: Boolean = false) {
        startActivity(
            CaseRecordActivity.intent(
                requireContext(),
                flowViewModel.currentImageUri(),
                flowViewModel.currentImportedAt(),
                flowViewModel.currentSummary().ifBlank {
                    getString(R.string.emergency_flow_recognition_no_result)
                },
                ArrayList(flowViewModel.currentCandidateLabels()),
                biteStatus,
            ),
        )
        if (auto) {
            // 自动弹出时说明一句背景，避免用户误以为咬伤情况已被系统判定过。
            Toast
                .makeText(
                    requireContext(),
                    getString(R.string.emergency_flow_case_auto_opened),
                    Toast.LENGTH_LONG,
                ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
