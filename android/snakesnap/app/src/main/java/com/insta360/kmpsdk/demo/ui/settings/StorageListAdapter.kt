package com.insta360.kmpsdk.demo.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arashivision.sdk.camera.core.model.option.StorageData
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.ItemStorageCardBinding
import com.insta360.kmpsdk.demo.ui.common.CameraDemoDisplayLabels

class StorageListAdapter(
    private val listener: IStorageItemClickListener,
) : ListAdapter<StorageData, StorageListAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemStorageCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, listener)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemStorageCardBinding,
        private val listener: IStorageItemClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StorageData) {
            val context = binding.root.context
            binding.storageLocationName.text =
                CameraDemoDisplayLabels.storageFileLocation(context, item.fileLocation)
            binding.storageState.text = CameraDemoDisplayLabels.storageState(context, item.state)
            binding.storageState.setTextColor(
                context.getColor(
                    if (item.state == StorageData.State.PASS) R.color.demo_green else R.color.demo_yellow,
                ),
            )
//            binding.btnFormat.isEnabled = item.state == StorageData.State.PASS
            binding.btnFormat.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) listener.onFormatClick(item.fileLocation)
            }
            binding.btnSetMainStorage.isVisible =
                item.fileLocation == StorageData.FileLocation.INNER ||
                    item.fileLocation == StorageData.FileLocation.SD
            binding.btnSetMainStorage.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) listener.onSetMainStorageClick(item.fileLocation)
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<StorageData>() {
            override fun areItemsTheSame(a: StorageData, b: StorageData) = a.fileLocation == b.fileLocation

            override fun areContentsTheSame(a: StorageData, b: StorageData) = a == b
        }
    }
}
