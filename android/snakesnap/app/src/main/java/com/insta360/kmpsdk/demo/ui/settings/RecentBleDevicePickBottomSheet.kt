package com.insta360.kmpsdk.demo.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.arashivision.sdk.camera.core.model.CameraType
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.data.RecentBleDevice
import com.insta360.kmpsdk.demo.databinding.DialogRecentBleDevicePickBottomSheetBinding
import com.insta360.kmpsdk.demo.databinding.ItemRecentBleDeviceBinding
import com.insta360.kmpsdk.demo.util.DemoAppPreferences
import com.insta360.kmpsdk.demo.util.formatLocalTime

/**
 * 蓝牙唤醒设备选择：列出最近成功连接过的相机（按时间倒序，最多 10 条）。
 * 选中后通过 [FragmentResult] 把 SN 透传给设置页发起 `bleWakeUp`。
 */
class RecentBleDevicePickBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogRecentBleDevicePickBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val adapter = RecentBleDeviceAdapter { record ->
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_SN to record.sn),
        )
        dismiss()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogRecentBleDevicePickBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.adapter = adapter
        adapter.submit(DemoAppPreferences.readRecentBleDevices(requireContext()))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recycler.adapter = null
        _binding = null
    }

    private class RecentBleDeviceAdapter(
        private val onPick: (RecentBleDevice) -> Unit,
    ) : RecyclerView.Adapter<RecentBleDeviceAdapter.Holder>() {

        private var items: List<RecentBleDevice> = emptyList()

        fun submit(list: List<RecentBleDevice>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = ItemRecentBleDeviceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return Holder(itemBinding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val record = items[position]
            val ctx = holder.itemView.context
            holder.binding.tvItemModel.text =
                ctx.getString(R.string.wake_item_model_fmt, record.cameraTypeKey)
            holder.binding.tvItemSn.text =
                ctx.getString(R.string.wake_item_sn_fmt, record.sn)
            holder.binding.tvItemTime.text =
                ctx.getString(R.string.wake_item_time_fmt, record.lastConnectedAt.formatLocalTime())
            // GO 系列不支持蓝牙广播唤醒，禁用按钮
            val cameraType = CameraType.getForType(record.cameraTypeKey)
            val isGoSeries = cameraType in GO_SERIES_TYPES
            holder.binding.btnItemWake.isEnabled = !isGoSeries
            holder.binding.btnItemWake.setOnClickListener { onPick(record) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(val binding: ItemRecentBleDeviceBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val REQUEST_KEY = "recent_ble_device_pick"
        const val RESULT_SN = "sn"

        private val GO_SERIES_TYPES = setOf(
            CameraType.GO, CameraType.GO_2, CameraType.GO_3, CameraType.GO_3S, CameraType.GO_ULTRA,
        )

        fun show(fm: FragmentManager) {
            RecentBleDevicePickBottomSheet().show(fm, "RecentBleDevicePickBottomSheet")
        }
    }
}
