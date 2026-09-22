package com.insta360.kmpsdk.demo.ui.connection

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentConnectionBinding
import kotlinx.coroutines.launch

class ConnectionFragment : Fragment() {
    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!

    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(
            requireActivity().application,
        )
    }

    private val scanAdapter =
        ScanDeviceAdapter(
            onBluetooth = { connectionViewModel.onConnectBluetooth(it) },
            onWifi = { connectionViewModel.onConnectWifiByBluetooth(it) },
            onWifiAware = { requestWifiAwarePermissionsThenConnect(it) },
            isWifiAwareSupported = { connectionViewModel.isWifiAwareSupported() },
        )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.scanRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.scanRecycler.adapter = scanAdapter
        binding.scanRecycler.isNestedScrollingEnabled = false

        binding.btnWifi.setOnClickListener { connectionViewModel.onConnectWifiClicked() }
        binding.btnScan.setOnClickListener { requestBleScanPermissionsThenStartScan() }
        binding.btnUsb.setOnClickListener { connectionViewModel.onUsbClicked() }
        binding.disconnectBtn.setOnClickListener { connectionViewModel.disconnect() }
        binding.refreshDynamicBtn.setOnClickListener { connectionViewModel.refreshDynamicInfo() }

        binding.entryPreview.setOnClickListener {
            findNavController().navigate(R.id.action_connectionFragment_to_previewFragment)
        }
        binding.entryNoPreview.setOnClickListener {
            findNavController().navigate(R.id.action_connectionFragment_to_captureFragment)
        }
        binding.entryLiveStream.setOnClickListener {
            findNavController().navigate(R.id.action_connectionFragment_to_liveStreamFragment)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectionViewModel.connectionUi.collect { state ->
                    binding.connectionResult.text = state.statusMessage
                    binding.scanRecycler.isVisible = state.scanListVisible && state.connectionButtonActiveIndex == 1
                    scanAdapter.submitList(state.scannedDevices)

                    styleConnectionButton(binding.btnWifi, state.connectionButtonActiveIndex == 0)
                    binding.btnWifi.isEnabled = state.connectState == ConnectState.Idle
                    styleConnectionButton(binding.btnScan, state.connectionButtonActiveIndex == 1)
                    binding.btnScan.isEnabled = state.connectState == ConnectState.Idle
                    styleConnectionButton(binding.btnUsb, state.connectionButtonActiveIndex == 2)
                    binding.btnUsb.isEnabled = state.connectState == ConnectState.Idle

                    binding.statusText.text = state.statusText
                    binding.disconnectBtn.isEnabled = state.connectState == ConnectState.Connected

                    val canRefreshDynamic =
                        state.connectState == ConnectState.Connected && !state.dynamicInfoRefreshing
                    binding.refreshDynamicBtn.isEnabled = canRefreshDynamic
                    binding.refreshDynamicBtn.text =
                        if (state.dynamicInfoRefreshing) {
                            getString(R.string.refreshing_dynamic_info)
                        } else {
                            getString(R.string.refresh_dynamic_info)
                        }

                    binding.deviceInfoCard.isVisible = state.deviceInfoVisible
                    binding.entryCaptureLayout.isVisible = state.deviceInfoVisible
                    binding.entryPreview.isVisible = state.previewCaptureEntryVisible
                    binding.entryLiveStream.isVisible = state.liveStreamEntryVisible

                    state.device?.let { d ->
                        binding.cameraModel.text = d.cameraType
                        binding.cameraSn.text = d.sn
                        binding.firmwareVersion.text = d.firmware
                        binding.activateTime.text = d.activated
                        binding.sdTotal.text = d.sdTotal
                        binding.internalTotal.text = d.internalTotal
                        binding.sdRemaining.text = d.sdRemaining
                        binding.sdStatus.text = d.sdStatus
                        binding.internalRemaining.text = d.internalRemaining
                        binding.internalStatus.text = d.internalStatus
                        binding.batteryLevel.text = d.batteryLevel
                        binding.chargingStatus.text = d.chargingStatus
                        binding.cameraTime.text = d.cameraTime
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 未授权时调 native 扫描易触发「ble scan reject」；此处先补齐权限再开始扫描。 */
    private fun requestBleScanPermissionsThenStartScan() {
        val req = XXPermissions.with(this.requireContext()).permission(Permission.Group.BLUETOOTH)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            req.permission(Permission.ACCESS_FINE_LOCATION, Permission.ACCESS_COARSE_LOCATION)
        }
        req.request { _, allGranted ->
            if (allGranted) {
                connectionViewModel.onScanClicked()
            } else {
                Toast
                    .makeText(
                        requireContext(),
                        getString(R.string.ble_scan_permissions_required),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    /** WiFi Aware 的 NAN 数据通道在 Android 13+ 需要 NEARBY_WIFI_DEVICES，低版本回退到定位权限。 */
    private fun requestWifiAwarePermissionsThenConnect(index: Int) {
        val req = XXPermissions.with(this.requireContext())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            req.permission(Permission.NEARBY_WIFI_DEVICES)
        } else {
            req.permission(Permission.ACCESS_FINE_LOCATION)
        }
        req.request { _, allGranted ->
            if (allGranted) {
                connectionViewModel.onConnectWiFiAwareByBluetooth(index)
            } else {
                Toast
                    .makeText(
                        requireContext(),
                        getString(R.string.wifi_aware_permissions_required),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }

    private fun styleConnectionButton(
        btn: MaterialButton,
        active: Boolean,
    ) {
        val color =
            ContextCompat.getColor(
                requireContext(),
                if (active) R.color.demo_accent else R.color.demo_button_dark,
            )
        btn.backgroundTintList = ColorStateList.valueOf(color)
    }

}
