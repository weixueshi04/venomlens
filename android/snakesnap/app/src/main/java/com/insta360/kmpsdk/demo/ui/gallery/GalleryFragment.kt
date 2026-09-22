package com.insta360.kmpsdk.demo.ui.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.data.GalleryLocationFilter
import com.insta360.kmpsdk.demo.data.GalleryTypeFilter
import com.insta360.kmpsdk.demo.databinding.FragmentGalleryBinding
import com.insta360.kmpsdk.demo.ui.common.DemoTopConnectionStatusBinder
import com.insta360.kmpsdk.demo.ui.connection.ConnectState
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import com.insta360.kmpsdk.demo.ui.player.image.ImagePlayerViewModel
import com.insta360.kmpsdk.demo.ui.player.video.VideoPlayerViewModel
import com.insta360.kmpsdk.demo.widget.enablePullToRefresh
import com.insta360.kmpsdk.demo.widget.finishRefresh
import kotlinx.coroutines.launch

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private var swipeRefreshLayout: SwipeRefreshLayout? = null

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    private val galleryViewModel: GalleryViewModel by viewModels {
        GalleryViewModel.Factory(connectionViewModel, requireActivity().application)
    }

    private val itemClickListener = object : IGalleryItemClickListener {

        override fun onPlayClick(index: Int) {
            galleryViewModel.play(index)
        }

        override fun onDownloadClick(index: Int) {
            galleryViewModel.downloadFiles(index)
        }

        override fun onDeleteClick(index: Int) {
            galleryViewModel.deleteFiles(index)
        }

        override fun onLoadThumbnail(index: Int) {
            galleryViewModel.loadThumbnail(index)
        }
    }

    private val adapter = GalleryAdapter(this.lifecycleScope, itemClickListener)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val span = 2
        binding.rvGallery.layoutManager = GridLayoutManager(requireContext(), span)
        binding.rvGallery.adapter = adapter
        swipeRefreshLayout = binding.rvGallery.enablePullToRefresh {
            galleryViewModel.reload()
        }

        binding.tabSourceCamera.setOnClickListener {
            galleryViewModel.setLocationFilter(GalleryLocationFilter.Camera)
        }
        binding.tabSourceLocal.setOnClickListener {
            galleryViewModel.setLocationFilter(GalleryLocationFilter.Local)
        }

        binding.tabCameraAll.setOnClickListener {
            galleryViewModel.setTypeFilter(GalleryTypeFilter.All)
        }
        binding.tabCameraPhoto.setOnClickListener {
            galleryViewModel.setTypeFilter(GalleryTypeFilter.Photo)
        }
        binding.tabCameraVideo.setOnClickListener {
            galleryViewModel.setTypeFilter(GalleryTypeFilter.Video)
        }


        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    galleryViewModel.ui.collect { state ->
                        swipeRefreshLayout?.finishRefresh()
                        adapter.submitList(state.items)
                        binding.tabSourceCamera.isSelected = state.locationFilter == GalleryLocationFilter.Camera
                        binding.tabSourceLocal.isSelected = state.locationFilter == GalleryLocationFilter.Local
                        val selectedId = when (state.typeFilter) {
                            GalleryTypeFilter.All -> binding.tabCameraAll.id
                            GalleryTypeFilter.Photo -> binding.tabCameraPhoto.id
                            GalleryTypeFilter.Video -> binding.tabCameraVideo.id
                        }
                        listOf(binding.tabCameraAll, binding.tabCameraPhoto, binding.tabCameraVideo).forEach {
                            it.isSelected = it.id == selectedId
                        }
                    }
                }
                launch {
                    connectionViewModel.connectionUi.collect { conn ->
                        DemoTopConnectionStatusBinder.bind(
                            requireContext(),
                            binding.layoutConnStatus.connectionStatusDot,
                            binding.layoutConnStatus.connectionStatusText,
                            conn,
                        )
                        if (conn.connectState != ConnectState.Connected) {
                            galleryViewModel.onCameraDisconnected()
                        }
                    }
                }
                launch {
                    galleryViewModel.uiEvent.collect { event ->
                        when (event) {
                            is GalleryUiEvent.Toast -> Toast.makeText(this@GalleryFragment.context, event.message, Toast.LENGTH_SHORT).show()
                            is GalleryUiEvent.PlayImage -> {
                                if (findNavController().currentDestination?.id == R.id.galleryFragment) {
                                    ImagePlayerViewModel.workWrapper = event.workWrapper
                                    findNavController().navigate(R.id.action_galleryFragment_to_imagePlayerFragment)
                                }
                            }

                            is GalleryUiEvent.PlayVideo -> {
                                if (findNavController().currentDestination?.id == R.id.galleryFragment) {
                                    VideoPlayerViewModel.workWrapper = event.workWrapper
                                    findNavController().navigate(R.id.action_galleryFragment_to_videoPlayerFragment)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        galleryViewModel.reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
