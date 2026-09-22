package com.insta360.kmpsdk.demo.ui.capture

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentPreviewParamsBottomSheetBinding

/** 预览参数 BottomSheet：配置 Demo 侧 player 渲染参数，不直接写 SDK 相机拍摄参数。 */
class PreviewParamsBottomSheetFragment : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.Theme_DemoBottomSheetDialog

    private var _binding: FragmentPreviewParamsBottomSheetBinding? = null
    private val binding get() = _binding!!
    private val rowsBinderState = CaptureParamRowsUi.BinderState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPreviewParamsBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        renderRows()
    }

    override fun onStart() {
        super.onStart()
        configureCaptureBottomSheet()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderRows() {
        val host = parentFragment as? PreviewFragment ?: return
        if (_binding == null) return
        CaptureParamRowsUi.render(
            binding.paramsContainer,
            host.previewParamRowsForSheet(),
            rowsBinderState,
        ) { key, index ->
            host.onPreviewParamSelectionChanged(key, index)
        }
    }

    fun refreshRows() {
        if (_binding == null) return
        renderRows()
    }

    companion object {
        internal const val TAG = "PreviewParamsBottomSheet"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) != null) return
            PreviewParamsBottomSheetFragment().show(manager, TAG)
        }
    }
}
