package com.insta360.kmpsdk.demo.ui.capture

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentCaptureParamsBottomSheetBinding
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import kotlinx.coroutines.launch

/** 拍摄参数 BottomSheet：展示 SDK `CameraCapture` 当前支持的拍摄参数。 */
class CaptureParamsBottomSheetFragment : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_DemoBottomSheetDialog

    private var _binding: FragmentCaptureParamsBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    private val captureViewModel: CameraCaptureViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
    ) {
        CameraCaptureViewModel.Factory(requireActivity().application) {
            connectionViewModel.getCameraDevice()
        }
    }

    private val rowsBinderState = CaptureParamRowsUi.BinderState()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCaptureParamsBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureViewModel.ui.collect { s ->
                    CaptureParamRowsUi.render(
                        binding.paramsContainer,
                        s.paramRows,
                        rowsBinderState,
                    ) { key, index ->
                        captureViewModel.onParamSelectionChanged(key, index)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        configureCaptureBottomSheet()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "CaptureParamsBottomSheet"

        fun show(manager: androidx.fragment.app.FragmentManager) {
            if (manager.findFragmentByTag(TAG) != null) return
            CaptureParamsBottomSheetFragment().show(manager, TAG)
        }
    }
}
