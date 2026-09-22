package com.insta360.kmpsdk.demo.ui.stitch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.insta360.kmpsdk.demo.databinding.FragmentStitchBinding
import com.insta360.kmpsdk.demo.ui.common.observeCameraDisconnectedNavigateToConnection
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class StitchFragment : Fragment() {

    private var _binding: FragmentStitchBinding? = null
    private val binding get() = _binding!!

    private val stitchViewModel: StitchViewModel by viewModels {
        StitchViewModel.Factory(requireActivity().application)
    }

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStitchBinding.inflate(inflater, container, false)
        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeCameraDisconnectedNavigateToConnection(connectionViewModel)

        initView()

        initUiState()
    }


    private fun initView() {

        binding.backLink.setOnClickListener { findNavController().popBackStack() }

        binding.btnStitchSeparatedFisheye.setOnClickListener {
            stitchViewModel.stitch()
        }
    }


    private fun initUiState() {
        // 所有 Flow 统一在 View 生命周期下执行
        val lifecycle = viewLifecycleOwner.lifecycle
        val scope = viewLifecycleOwner.lifecycleScope

        stitchViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { arrayOf(it.fisheyeFrontPath, it.fisheyeRearPath) }
            .onEach {
                Glide.with(this@StitchFragment).load(it.fisheyeFrontPath).into(binding.ivFisheyeFront)
                Glide.with(this@StitchFragment).load(it.fisheyeRearPath).into(binding.ivFisheyeRear)
            }
            .launchIn(scope)

        stitchViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { it.resultPath }
            .onEach {
                Glide.with(this@StitchFragment).load(it.resultPath).into(binding.ivStitchResult)
            }
            .launchIn(scope)

        stitchViewModel.uiEvent
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { event ->
                when (event) {
                    is StitchUiEvent.Toast -> Toast.makeText(this@StitchFragment.context, event.message, Toast.LENGTH_SHORT).show()
                    StitchUiEvent.HideLoading -> binding.loadingView.visibility = View.GONE

                    is StitchUiEvent.ShowLoading -> {
                        binding.loadingView.setText(event.message)
                        binding.loadingView.visibility = View.VISIBLE
                    }
                }
            }
            .launchIn(scope)
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
