package com.insta360.kmpsdk.demo.ui.connection

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.sdk.camera.core.model.CameraType
import com.insta360.kmpsdk.demo.databinding.ItemScanDeviceBinding

class ScanDeviceAdapter(
    private val onBluetooth: (Int) -> Unit,
    private val onWifi: (Int) -> Unit,
    private val onWifiAware: (Int) -> Unit = {},
    private val isWifiAwareSupported: () -> Boolean = { false },
) : ListAdapter<BleDeviceCore, ScanDeviceAdapter.VH>(DIFF) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VH {
        val binding =
            ItemScanDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(
        holder: VH,
        position: Int,
    ) {
        holder.bind(getItem(position), position)
    }

    inner class VH(
        private val binding: ItemScanDeviceBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            device: BleDeviceCore,
            index: Int,
        ) {
            binding.scanName.text = device.name
            binding.scanMac.text = device.address
            binding.btnBluetooth.setOnClickListener { onBluetooth(index) }
            binding.btnWifi.setOnClickListener { onWifi(index) }

            // 仅 X6 支持 WiFi Aware，且需手机端硬件/系统版本支持才显示入口
            val awareVisible =
                isWifiAwareSupported() && CameraType.getForSimpleName(device.name) == CameraType.X6
            binding.btnWifiAware.isVisible = awareVisible
            binding.btnWifiAware.setOnClickListener { onWifiAware(index) }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<BleDeviceCore>() {
                override fun areItemsTheSame(
                    a: BleDeviceCore,
                    b: BleDeviceCore,
                ) = a.address == b.address

                override fun areContentsTheSame(
                    a: BleDeviceCore,
                    b: BleDeviceCore,
                ) = a == b
            }
    }
}
