package com.insta360.kmpsdk.demo.ui.gallery

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.ItemGalleryMediaBinding
import com.insta360.kmpsdk.demo.ext.roundTo
import com.insta360.kmpsdk.demo.ext.speedFormat
import com.insta360.kmpsdk.demo.util.TaskExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryAdapter(
    private val scope: CoroutineScope,
    val listener: IGalleryItemClickListener
) : ListAdapter<GalleryUiItemState, GalleryAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemGalleryMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(scope, binding, listener)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bindFull(getItem(position), position)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = getItem(position)
        for (p in payloads) {
            when (p) {
                PAYLOAD_THUMBNAIL -> holder.bindThumbnailOnly(item, position)
                PAYLOAD_DOWNLOAD_UI -> holder.bindDownloadDeleteUiOnly(item, position)
                else -> {
                    onBindViewHolder(holder, position)
                    return
                }
            }
        }
    }

    class VH(
        private val scope: CoroutineScope,
        private val binding: ItemGalleryMediaBinding,
        val listener: IGalleryItemClickListener
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n")
        fun bindFull(item: GalleryUiItemState, @Suppress("UNUSED_PARAMETER") position: Int) {
            binding.fileName.text = item.key
            bindRootClick(item)
            binding.btnDownload.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) listener.onDownloadClick(p)
            }
            binding.btnDelete.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) listener.onDeleteClick(p)
            }
            binding.btnDownload.visibility = if (item.isLocal) View.GONE else View.VISIBLE
            scope.launch(TaskExecutor.dispatcher) {
                withContext(Dispatchers.Main) {
                    item.thumbnail?.let { bmp ->
                        binding.ivThumb.setImageBitmap(bmp)
                    } ?: run {
                        binding.ivThumb.setImageResource(R.drawable.ic_image_default)
                        val p = bindingAdapterPosition
                        if (p != RecyclerView.NO_POSITION) listener.onLoadThumbnail(p)
                    }
                    binding.fileName.text = ""
                    binding.fileSizeValue.text = item.size
                    binding.fileDateValue.text = item.date
                    binding.fileResolutionValue.text = item.resolution
                    binding.fileVideoDuration.visibility = if (item.isVideo) View.VISIBLE else View.GONE
                    binding.fileVideoDurationValue.visibility = if (item.isVideo) View.VISIBLE else View.GONE
                    binding.fileVideoDurationValue.text = item.duration
                    binding.fileCameraTypeValue.text = item.cameraType
                    item.functionModeIconResId?.let { resId ->
                        binding.ivFunctionMode.visibility = View.VISIBLE
                        binding.ivFunctionMode.setImageResource(resId)
                    } ?: run {
                        binding.ivFunctionMode.visibility = View.GONE
                    }
                    bindDownloadDeleteUiOnly(item, position)
                }
            }
        }

        @SuppressLint("SetTextI18n")
        fun bindThumbnailOnly(item: GalleryUiItemState, @Suppress("UNUSED_PARAMETER") position: Int) {
            bindRootClick(item)
            item.thumbnail?.let { bmp ->
                binding.ivThumb.setImageBitmap(bmp)
            } ?: run {
                binding.ivThumb.setImageResource(R.drawable.ic_image_default)
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) listener.onLoadThumbnail(p)
            }
        }

        private fun bindRootClick(item: GalleryUiItemState) {
            binding.root.setOnClickListener {
                val p = bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                if (item.thumbnail != null) {
                    listener.onPlayClick(p)
                } else {
                    Toast.makeText(
                        binding.root.context,
                        R.string.gallery_thumbnail_loading_hint,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        @SuppressLint("SetTextI18n")
        fun bindDownloadDeleteUiOnly(item: GalleryUiItemState, @Suppress("UNUSED_PARAMETER") position: Int) {
            if (item.isDownloading) {
                binding.downloadingView.start()
                val currentProgress = item.downloadFileProgress.toDouble() / item.downloadFileTotal * 100
                binding.downloadingView.setProgress("下载进度：${currentProgress.roundTo(2)}% · ${item.downloadSpeedBps.speedFormat()}")
            } else {
                binding.downloadingView.stop()
            }
            if (item.isDeleting) {
                binding.deletingView.start()
            } else {
                binding.deletingView.stop()
            }
        }
    }

    private companion object {
        const val PAYLOAD_THUMBNAIL = "gallery_thumb"
        const val PAYLOAD_DOWNLOAD_UI = "gallery_download_ui"

        val DIFF = object : DiffUtil.ItemCallback<GalleryUiItemState>() {
            override fun areItemsTheSame(a: GalleryUiItemState, b: GalleryUiItemState) = a.key == b.key

            override fun areContentsTheSame(a: GalleryUiItemState, b: GalleryUiItemState) = a == b

            override fun getChangePayload(
                a: GalleryUiItemState,
                b: GalleryUiItemState
            ): Any? {
                if (a == b) return null
                if (!a.sameDisplayMetaAs(b)) return null
                if (a.sameDownloadBlockAs(b) && a.thumbnail === b.thumbnail) {
                    // 理论上 areContentsTheSame 已为 true，不应对 diff 的 payload 被调用
                    return null
                }
                if (a.sameDownloadBlockAs(b)) return PAYLOAD_THUMBNAIL
                if (a.thumbnail === b.thumbnail) return PAYLOAD_DOWNLOAD_UI
                return null
            }
        }
    }
}

/** 元数据/类型不变时用于 Diff 局部刷新判断（不含缩略图与下载/删除状态） */
private fun GalleryUiItemState.sameDisplayMetaAs(other: GalleryUiItemState): Boolean =
    key == other.key &&
            size == other.size &&
            resolution == other.resolution &&
            date == other.date &&
            duration == other.duration &&
            cameraType == other.cameraType &&
            isLocal == other.isLocal &&
            isVideo == other.isVideo &&
            functionModeIconResId == other.functionModeIconResId

private fun GalleryUiItemState.sameDownloadBlockAs(other: GalleryUiItemState): Boolean =
    isDownloading == other.isDownloading &&
            isDeleting == other.isDeleting &&
            downloadFileProgress == other.downloadFileProgress &&
            downloadFileTotal == other.downloadFileTotal &&
            downloadSpeedBps == other.downloadSpeedBps
