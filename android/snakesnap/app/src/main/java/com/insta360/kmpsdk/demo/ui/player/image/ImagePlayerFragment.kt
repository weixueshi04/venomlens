package com.insta360.kmpsdk.demo.ui.player.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.arashivision.sdk.media.api.common.OffsetType
import com.arashivision.sdk.media.api.common.StabType
import com.arashivision.sdk.media.api.listener.PlayerViewListener
import com.arashivision.sdk.media.api.params.ImagePlayerParams
import com.arashivision.sdk.media.player.image.InstaImagePlayerView
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentImagePlayerBinding
import com.insta360.kmpsdk.demo.ui.common.DemoMessageDialog.showUpdatableDialog
import com.insta360.kmpsdk.demo.ui.common.DemoUpdatableDialog
import com.insta360.kmpsdk.demo.ui.common.observeCameraDisconnectedNavigateToConnection
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import com.insta360.kmpsdk.demo.ui.player.adapter.ScreenRate
import com.insta360.kmpsdk.demo.ui.player.adapter.SettingAdapter
import com.insta360.kmpsdk.demo.ui.player.adapter.SettingType
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

class ImagePlayerFragment : Fragment() {

    private var _binding: FragmentImagePlayerBinding? = null
    private val binding get() = _binding!!

    private val playerSettingAdapter = SettingAdapter()
    private val exportSettingAdapter = SettingAdapter()

    private lateinit var imagePlayerView: InstaImagePlayerView

    private var demoUpdatableDialog: DemoUpdatableDialog? = null

    private val imagePlayerViewModel: ImagePlayerViewModel by viewModels {
        ImagePlayerViewModel.Factory(requireActivity().application)
    }

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImagePlayerBinding.inflate(inflater, container, false)
        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeCameraDisconnectedNavigateToConnection(connectionViewModel)

        initView()

        initUiState()

        InstaImagePlayerView(requireContext()).also { playerView ->
            imagePlayerView = playerView
            playerView.setLifecycle(this@ImagePlayerFragment.lifecycle)
            binding.flPlayerContainer.addView(
                playerView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }

        binding.btnLoadExtra.setOnClickListener {
            imagePlayerViewModel.loadExtraData()
        }

        binding.btnStartPlay.setOnClickListener {
            imagePlayerViewModel.play()
        }

        binding.btnStopPlay.setOnClickListener {
            imagePlayerViewModel.stop()
        }

        binding.backLink.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnNext.setOnClickListener {
            imagePlayerViewModel.next()
        }

        binding.btnPrevious.setOnClickListener {
            imagePlayerViewModel.previous()
        }
    }


    private fun initView() {

        binding.backLink.setOnClickListener { findNavController().popBackStack() }

        // 点击导出
        binding.btnExport.setOnClickListener {
            binding.clExport.visibility = View.VISIBLE
            exportSettingAdapter.submitList(imagePlayerViewModel.ui.value.settings.filter { it.first.isImageExport })
        }

        // 点击取消导出
        binding.btnExportCancel.setOnClickListener {
            binding.clExport.visibility = View.GONE
        }

        // 点击确认导出
        binding.btnConfirmExport.setOnClickListener {
            binding.clExport.visibility = View.GONE
            imagePlayerViewModel.startExportImage(
                imagePlayerView.getYaw(),
                imagePlayerView.getDistance(),
                imagePlayerView.getFov(),
                imagePlayerView.getPitch()
            )
        }

        //点击播放设置
        binding.btnPlayerSettings.setOnClickListener {
            binding.clPlayerSetting.visibility = View.VISIBLE
            playerSettingAdapter.submitList(imagePlayerViewModel.ui.value.settings.filter { it.first.isPlay })
        }

        // 点击取消设置
        binding.btnPlayerCancel.setOnClickListener {
            binding.clPlayerSetting.visibility = View.GONE
        }

        // 点击HDR
        binding.btnHdr.setOnClickListener {
            imagePlayerViewModel.onHdrClick()
        }

        // 点击PureShot
        binding.btnPureShot.setOnClickListener {
            imagePlayerViewModel.onPureShotClick()
        }

        // 初始化播放设置的选项页面
        binding.rvPlayerSettings.layoutManager = LinearLayoutManager(requireContext())
        playerSettingAdapter.setOnItemSelectListener { type, option ->
            imagePlayerViewModel.setParams(type, option)
            binding.clPlayerSetting.visibility = View.GONE
        }
        binding.rvPlayerSettings.adapter = playerSettingAdapter
        binding.rvPlayerSettings.isNestedScrollingEnabled = false

        // 初始化导出选项页面
        binding.rvExportSettings.layoutManager = LinearLayoutManager(requireContext())
        exportSettingAdapter.setOnItemSelectListener { type, option ->
            imagePlayerViewModel.setParams(type, option)
        }
        binding.rvExportSettings.adapter = exportSettingAdapter
        binding.rvExportSettings.isNestedScrollingEnabled = false
    }

    private fun initUiState() {
        // 所有 Flow 统一在 View 生命周期下执行
        val lifecycle = viewLifecycleOwner.lifecycle
        val scope = viewLifecycleOwner.lifecycleScope

        // 1. 监听按钮状态
        imagePlayerViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy {
                listOf(
                    it.isLoadedExtraData,
                    it.count,
                    it.supportPureShotGenerate,
                    it.supportHdrGenerate,
                    it.hdrGeneratePath,
                    it.isShowHdrGenerate,
                    it.pureShotGeneratePath,
                    it.isShowPureShotGenerate
                )
            }
            .onEach {
                binding.btnLoadExtra.setText(
                    if (it.isLoadedExtraData) R.string.player_load_extra_data_success else R.string.player_load_extra_data
                )

                binding.btnPrevious.visibility = if (it.count > 1) View.VISIBLE else View.GONE
                binding.btnNext.visibility = if (it.count > 1) View.VISIBLE else View.GONE

                if (it.supportHdrGenerate) {
                    binding.btnHdr.visibility = View.VISIBLE
                    binding.btnHdr.setText(
                        if (it.hdrGeneratePath.isEmpty())
                            R.string.player_hdr_generate
                        else if (!it.isShowHdrGenerate)
                            R.string.player_show_hdr_generate
                        else
                            R.string.player_show_original
                    )
                } else {
                    binding.btnHdr.visibility = View.GONE
                }

                if (it.supportPureShotGenerate) {
                    binding.btnPureShot.visibility = View.VISIBLE
                    binding.btnPureShot.setText(
                        if (it.pureShotGeneratePath.isEmpty())
                            R.string.player_pure_shot_generate
                        else if (!it.isShowPureShotGenerate)
                            R.string.player_show_pure_shot_generate
                        else
                            R.string.player_show_original
                    )
                } else {
                    binding.btnPureShot.visibility = View.GONE
                }
            }
            .launchIn(scope)

        // 2. 设置列表
        imagePlayerViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { it.settings }
            .onEach {
                playerSettingAdapter.submitList(it.settings.filter { it.first.isPlay })
                exportSettingAdapter.submitList(it.settings.filter { it.first.isImageExport })
            }
            .launchIn(scope)

        // 3. 导出进度
        imagePlayerViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { it.exportProgress }
            .onEach {
                demoUpdatableDialog?.updateMessage("${(it.exportProgress * 100).toInt()}%")
            }
            .launchIn(scope)

        // 4. 下标事件
        imagePlayerViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { it.index }
            .onEach {
                imagePlayerView.destroy()
                imagePlayerViewModel.play()
            }
            .launchIn(scope)

        // 5. UI 事件（并行，不等待上面执行完）
        imagePlayerViewModel.uiEvent
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { event ->
                when (event) {
                    is ImageUiEvent.Toast -> Toast.makeText(this@ImagePlayerFragment.context, event.message, Toast.LENGTH_SHORT).show()
                    is ImageUiEvent.Play -> playImage(event.params, event.playerViewListener)
                    ImageUiEvent.Stop -> imagePlayerView.destroy()
                    is ImageUiEvent.SetParams -> when (event.type) {
                        SettingType.PREVIEW_MODE -> {
                            when (event.option.id) {
                                0 -> imagePlayerView.switchNormalMode()
                                1 -> imagePlayerView.switchFisheyeMode()
                                2 -> imagePlayerView.switchPerspectiveMode()
                            }
                        }

                        SettingType.DYNAMIC_STITCH -> (event.option.value as? Boolean)?.let { imagePlayerView.setDynamicStitchEnabled(it) }
                        SettingType.RENDER_MODE -> {
                            imagePlayerView.destroy()
                            imagePlayerViewModel.play()
                        }

                        SettingType.COLOR_PLUS -> (event.option.value as? Boolean)?.let { imagePlayerView.setColorPlusEnabled(it) }
                        SettingType.COLOR_PLUS_INTENSITY -> {
                            if (imagePlayerView.isColorPlusEnabled()) {
                                (event.option.value as? Float)?.let { imagePlayerView.setColorPlusFilterIntensity(it) }
                            }
                        }

                        SettingType.IMAGE_FUSION -> (event.option.value as? Boolean)?.let { imagePlayerView.setColorFusionEnabled(it) }
                        SettingType.SCREEN_RATE -> (event.option.value as? ScreenRate)?.toIntArray()?.let { imagePlayerView.setScreenRatio(it[0], it[1]) }
                        SettingType.OFFSET_TYPE -> (event.option.value as? OffsetType)?.let { imagePlayerView.setOffsetType(it) }
                        SettingType.STAB_TYPE -> (event.option.value as? StabType)?.let { imagePlayerView.setStabType(it) }
                        SettingType.DE_PURPLE_FILTER -> (event.option.value as? Boolean)?.let { imagePlayerView.setDePurpleFilterEnable(it) }
                        else -> {}
                    }

                    ImageUiEvent.Pop -> findNavController().popBackStack()
                    ImageUiEvent.HideExportDialog -> {
                        demoUpdatableDialog?.dismiss()
                        demoUpdatableDialog = null
                    }

                    ImageUiEvent.HideLoading -> binding.loadingView.visibility = View.GONE
                    ImageUiEvent.ShowExportDialog -> {
                        if (demoUpdatableDialog == null) {
                            demoUpdatableDialog = showUpdatableDialog(this@ImagePlayerFragment, "正在导出", "0%", false) {
                                if (it) imagePlayerViewModel.stopExportImage()
                            }
                        }
                    }

                    is ImageUiEvent.ShowLoading -> {
                        binding.loadingView.setText(event.message)
                        binding.loadingView.visibility = View.VISIBLE
                    }
                }
            }
            .launchIn(scope)
    }

    private fun playImage(params: ImagePlayerParams, listener: PlayerViewListener) {
        Timber.d("params index ${params.index}")
        imagePlayerView.prepare(params)
        imagePlayerView.setListener(listener)
        imagePlayerView.play()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        imagePlayerView.destroy()
    }
}
