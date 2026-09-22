package com.insta360.kmpsdk.demo.ui.capture

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.arashivision.sdk.camera.core.model.FunctionMode
import com.arashivision.sdk.camera.core.model.option.SensorMode
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentCaptureBinding
import com.insta360.kmpsdk.demo.ui.common.CameraDemoDisplayLabels
import com.insta360.kmpsdk.demo.ui.common.observeCameraDisconnectedNavigateToConnection
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import kotlinx.coroutines.launch

/** 不带预览的拍摄页：仅通过 CameraCapture 控制拍摄参数与开始/停止，无预览流。 */
class CaptureFragment : Fragment() {
    private var _binding: FragmentCaptureBinding? = null
    private val binding get() = _binding!!

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    private val captureViewModel: CameraCaptureViewModel by viewModels {
        CameraCaptureViewModel.Factory(requireActivity().application) {
            connectionViewModel.getCameraDevice()
        }
    }

    private var lastModeOptions: List<FunctionMode> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        observeCameraDisconnectedNavigateToConnection(connectionViewModel)

        binding.backLink.setOnClickListener { findNavController().popBackStack() }
        binding.openCaptureParamsSheet.setOnClickListener {
            CaptureParamsBottomSheetFragment.show(childFragmentManager)
        }
        binding.captureBtn.setOnClickListener {
            captureViewModel.onPrimaryCaptureButtonClicked()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureViewModel.ui.collect { s ->
                    // busy（切镜头/模式）与拍摄命令在途共用同一全屏遮罩
                    val blocking = s.busy || s.captureCommand != null
                    binding.blockingOverlay.isVisible = blocking
                    binding.blockingOverlay.setText(s.loadingText?:"")
                    binding.lensSection.isVisible = s.lensRowVisible
                    rebuildLensChips(s.lensOptions, s.selectedLens)
                    rebuildModeChips(s.modeOptions, s.selectedMode)
                    binding.captureBtn.text = s.captureButtonLabel
                    binding.captureBtn.isEnabled = s.captureButtonEnabled && !blocking
                    binding.captureProgressLabel.text = s.progressLabel.orEmpty()
                    binding.captureProgressLabel.isVisible = !s.progressLabel.isNullOrEmpty()
                    binding.captureSubStatusLabel.text = s.subStatusLabel.orEmpty()
                    binding.captureSubStatusLabel.isVisible = !s.subStatusLabel.isNullOrEmpty()
                    binding.resultPanel.isVisible = s.resultVisible
                    binding.resultSummary.text = s.resultSummary
                    applySecondaryInteractionLock(blocking || s.captureFlowActive)
                }
            }
        }

        captureViewModel.onAppear()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            if (captureViewModel.isLowPowerDevice()) {
                captureViewModel.lockCameraScreen()
            }
        }
    }

    private fun rebuildLensChips(
        options: List<SensorMode>,
        selected: SensorMode?,
    ) {
        CaptureChips.render(
            container = binding.lensChipContainer,
            options = options,
            selected = selected,
            textSizeSp = 14f,
            label = { CameraDemoDisplayLabels.sensorMode(requireContext(), it) },
            onClick = captureViewModel::selectLens,
        )
    }

    private fun rebuildModeChips(
        options: List<FunctionMode>,
        selected: FunctionMode?,
    ) {
        lastModeOptions =
            CaptureChips.renderStableModeStrip(
                scroll = binding.modeScroll,
                container = binding.modeChipContainer,
                previousOptions = lastModeOptions,
                options = options,
                selected = selected,
                label = { CameraDemoDisplayLabels.functionMode(requireContext(), it) },
                onClick = captureViewModel::selectMode,
            )
    }

    private fun applySecondaryInteractionLock(locked: Boolean) {
        val alpha = if (locked) 0.45f else 1f
        listOf(binding.backLink, binding.openCaptureParamsSheet).forEach {
            it.isEnabled = !locked
            it.alpha = alpha
        }
        CaptureChips.setInteractionLocked(binding.lensChipContainer, locked)
        CaptureChips.setInteractionLocked(binding.modeChipContainer, locked)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
