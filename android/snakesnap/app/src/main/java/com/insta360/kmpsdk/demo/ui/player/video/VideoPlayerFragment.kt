package com.insta360.kmpsdk.demo.ui.player.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.arashivision.sdk.media.api.common.OffsetType
import com.arashivision.sdk.media.api.common.StabType
import com.arashivision.sdk.media.api.listener.PlayerViewListener
import com.arashivision.sdk.media.api.listener.VideoStatusListener
import com.arashivision.sdk.media.api.params.VideoPlayerParams
import com.arashivision.sdk.media.player.video.InstaVideoPlayerView
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentVideoPlayerBinding
import com.insta360.kmpsdk.demo.ext.durationFormat
import com.insta360.kmpsdk.demo.ui.common.CameraDemoDisplayLabels
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

class VideoPlayerFragment : Fragment() {

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!

    private val playSettingAdapter = SettingAdapter()
    private val exportSettingAdapter = SettingAdapter()

    private lateinit var videoPlayerView: InstaVideoPlayerView

    private var demoUpdatableDialog: DemoUpdatableDialog? = null

    private val videoPlayerViewModel: VideoPlayerViewModel by viewModels {
        VideoPlayerViewModel.Factory(requireActivity().application)
    }

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeCameraDisconnectedNavigateToConnection(connectionViewModel)

        initView()

        initUiState()

        InstaVideoPlayerView(requireContext()).also { playerView ->
            videoPlayerView = playerView
            playerView.setLifecycle(this@VideoPlayerFragment.lifecycle)
            binding.flPlayerContainer.addView(
                playerView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }

        binding.btnLoadExtra.setOnClickListener {
            videoPlayerViewModel.loadExtraData()
        }

        binding.btnStartPlay.setOnClickListener {
            videoPlayerViewModel.play()
        }

        binding.btnPausePlay.setOnClickListener {
            videoPlayerViewModel.pause()
        }

        binding.btnResumePlay.setOnClickListener {
            videoPlayerViewModel.resume()
        }

        binding.btnStopPlay.setOnClickListener {
            videoPlayerViewModel.stop()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                videoPlayerView.pause()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                videoPlayerView.seekTo(seekBar.progress.toLong())
            }
        })

        binding.backLink.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun initView() {

        binding.backLink.setOnClickListener { findNavController().popBackStack() }

        // 点击导出
        binding.btnExport.setOnClickListener {
            binding.clExport.visibility = View.VISIBLE
            exportSettingAdapter.submitList(videoPlayerViewModel.ui.value.settings.filter { it.first.isVideoExport })
        }

        // 点击取消导出
        binding.btnExportCancel.setOnClickListener {
            binding.clExport.visibility = View.GONE
        }

        // 点击确认导出
        binding.btnExportVideo.setOnClickListener {
            binding.clExport.visibility = View.GONE
            videoPlayerViewModel.startExportVideo(
                videoPlayerView.getYaw(),
                videoPlayerView.getDistance(),
                videoPlayerView.getFov(),
                videoPlayerView.getPitch(),
                videoPlayerView.getRoll()
            )
        }

        // 点击确认导出
        binding.btnExportImages.setOnClickListener {
            binding.clExport.visibility = View.GONE
            videoPlayerViewModel.startExportImages(
                videoPlayerView.getYaw(),
                videoPlayerView.getDistance(),
                videoPlayerView.getFov(),
                videoPlayerView.getPitch(),
                videoPlayerView.getCurrentPosition().toDouble()
            )
        }

        //点击播放设置
        binding.btnPlayerSettings.setOnClickListener {
            binding.clPlayerSetting.visibility = View.VISIBLE
            playSettingAdapter.submitList(videoPlayerViewModel.ui.value.settings.filter { it.first.isPlay })
        }

        // 点击取消设置
        binding.btnPlayerCancel.setOnClickListener {
            binding.clPlayerSetting.visibility = View.GONE
        }

        // 初始化播放设置的选项页面
        binding.rvPlayerSettings.layoutManager = LinearLayoutManager(requireContext())
        playSettingAdapter.setOnItemSelectListener { type, option ->
            videoPlayerViewModel.setParams(type, option)
            binding.clPlayerSetting.visibility = View.GONE
        }
        binding.rvPlayerSettings.adapter = playSettingAdapter
        binding.rvPlayerSettings.isNestedScrollingEnabled = false

        // 初始化导出选项页面
        binding.rvExportSettings.layoutManager = LinearLayoutManager(requireContext())
        exportSettingAdapter.setOnItemSelectListener { type, option ->
            videoPlayerViewModel.setParams(type, option)
        }
        binding.rvExportSettings.adapter = exportSettingAdapter
        binding.rvExportSettings.isNestedScrollingEnabled = false
    }

    private fun initUiState() {
        // 所有 Flow 统一在 View 生命周期下执行
        val lifecycle = viewLifecycleOwner.lifecycle
        val scope = viewLifecycleOwner.lifecycleScope

        // 1. 监听按钮状态
        videoPlayerViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { it.isLoadedExtraData }
            .onEach {
                binding.btnLoadExtra.setText(
                    if (it.isLoadedExtraData) R.string.player_load_extra_data_success
                    else R.string.player_load_extra_data
                )
            }
            .launchIn(scope)

        // 2. 设置列表
        videoPlayerViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { it.settings }
            .onEach {
                playSettingAdapter.submitList(it.settings.filter { it.first.isPlay })
                exportSettingAdapter.submitList(it.settings.filter { it.first.isVideoExport })
            }
            .launchIn(scope)

        // 3. 播放进度
        videoPlayerViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { it.videoPlayProgress }
            .onEach {
                binding.seekBar.max = it.videoPlayProgress.second.toInt()
                binding.seekBar.progress = it.videoPlayProgress.first.toInt()
                binding.tvCurrent.text = it.videoPlayProgress.first.durationFormat()
                binding.tvTotal.text = it.videoPlayProgress.second.durationFormat()
            }
            .launchIn(scope)

        // 4. 导出进度
        videoPlayerViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { it.exportProgress }
            .onEach {
                demoUpdatableDialog?.updateMessage("${(it.exportProgress * 100).toInt()}%")
            }
            .launchIn(scope)

        // 5. 播放流畅度检测结果
        videoPlayerViewModel.ui
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChangedBy { it.playingFluencyStatus }
            .onEach {
                val status = it.playingFluencyStatus
                binding.tvPlayingFluency.isVisible = status != PlayingFluencyStatus.UNKNOWN
                binding.tvPlayingFluency.text = CameraDemoDisplayLabels.playingFluencyStatus(requireContext(), status)
                binding.tvPlayingFluency.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        when (status) {
                            PlayingFluencyStatus.SMOOTH -> R.color.demo_badge_text
                            PlayingFluencyStatus.STUTTER -> R.color.live_red
                            PlayingFluencyStatus.DETECT_FAILED -> R.color.demo_hint
                            PlayingFluencyStatus.UNKNOWN -> R.color.demo_hint
                        }
                    )
                )
            }
            .launchIn(scope)

        // 6. UI 事件（并行，不等待上面执行完）
        videoPlayerViewModel.uiEvent
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { event ->
                when (event) {
                    is VideoUiEvent.Toast -> Toast.makeText(this@VideoPlayerFragment.context, event.message, Toast.LENGTH_SHORT).show()
                    is VideoUiEvent.Play -> playVideo(event.params, event.playerViewListener, event.videoStatusListener)
                    VideoUiEvent.Pause -> videoPlayerView.pause()
                    VideoUiEvent.Resume -> videoPlayerView.resume()
                    VideoUiEvent.Stop -> videoPlayerView.destroy()
                    is VideoUiEvent.SetParams -> when (event.type) {
                        SettingType.PREVIEW_MODE -> {
                            when (event.option.id) {
                                0 -> videoPlayerView.switchNormalMode()
                                1 -> videoPlayerView.switchFisheyeMode()
                                2 -> videoPlayerView.switchPerspectiveMode()
                            }
                        }

                        SettingType.DYNAMIC_STITCH -> (event.option.value as? Boolean)?.let { videoPlayerView.setDynamicStitchEnabled(it) }
                        SettingType.DE_PURPLE_FILTER -> (event.option.value as? Boolean)?.let { videoPlayerView.setDePurpleFilterEnable(it) }
                        SettingType.COLOR_PLUS -> (event.option.value as? Boolean)?.let { videoPlayerView.setColorPlusEnabled(it) }
                        SettingType.COLOR_PLUS_INTENSITY -> {
                            if (videoPlayerView.isColorPlusEnabled()) {
                                (event.option.value as? Float)?.let { videoPlayerView.setColorPlusFilterIntensity(it) }
                            }
                        }

                        SettingType.IMAGE_FUSION -> (event.option.value as? Boolean)?.let { videoPlayerView.setColorFusionEnabled(it) }
                        SettingType.SUB_STREAM -> (event.option.value as? Boolean)?.let { videoPlayerView.setLrvEnable(it) }
                        SettingType.SCREEN_RATE -> (event.option.value as? ScreenRate)?.toIntArray()?.let { videoPlayerView.setScreenRatio(it[0], it[1]) }
                        SettingType.RENDER_MODE -> {
                            videoPlayerView.destroy()
                            videoPlayerViewModel.play()
                        }

                        SettingType.OFFSET_TYPE -> (event.option.value as? OffsetType)?.let { videoPlayerView.setOffsetType(it) }
                        SettingType.STAB_TYPE -> (event.option.value as? StabType)?.let { videoPlayerView.setStabType(it) }
                        else -> {}
                    }

                    VideoUiEvent.SeekComplete -> videoPlayerView.resume()

                    VideoUiEvent.Pop -> findNavController().popBackStack()
                    VideoUiEvent.HideExportDialog -> {
                        demoUpdatableDialog?.dismiss()
                        demoUpdatableDialog = null
                    }

                    VideoUiEvent.HideLoading -> binding.loadingView.visibility = View.GONE

                    VideoUiEvent.ShowExportDialog -> {
                        if (demoUpdatableDialog == null) {
                            demoUpdatableDialog = showUpdatableDialog(this@VideoPlayerFragment, "正在导出", "0%", false) {
                                if (it) videoPlayerViewModel.stopExportVideo()
                            }
                        }
                    }

                    VideoUiEvent.ShowLoading -> binding.loadingView.visibility = View.VISIBLE
                }
            }
            .launchIn(scope)
    }


    private fun playVideo(params: VideoPlayerParams, playerViewListener: PlayerViewListener, videoStatusListener: VideoStatusListener) {
        videoPlayerView.prepare(params)
        videoPlayerView.setListener(playerViewListener)
        videoPlayerView.setVideoStatusListener(videoStatusListener)
        videoPlayerView.play()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        videoPlayerView.destroy()
    }
}
