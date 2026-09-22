package com.insta360.kmpsdk.demo.ui.settings

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.arashivision.sdk.camera.core.model.ConnectType
import com.arashivision.sdk.camera.core.model.option.StorageData
import com.arashivision.sdk.common.log.LogLevel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.FragmentSettingsBinding
import com.insta360.kmpsdk.demo.ui.common.CameraDemoDisplayLabels
import com.insta360.kmpsdk.demo.ui.common.DemoMessageDialog
import com.insta360.kmpsdk.demo.ui.common.DemoTopConnectionStatusBinder
import com.insta360.kmpsdk.demo.ui.common.DemoUpdatableDialog
import com.insta360.kmpsdk.demo.ui.common.showMessageDialog
import com.insta360.kmpsdk.demo.ui.connection.ConnectState
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import com.insta360.kmpsdk.demo.util.DemoAppPreferences
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var cameraLogProgressDialog: DemoUpdatableDialog? = null
    private var demoLogProgressDialog: DemoUpdatableDialog? = null
    private var firmwareUpgradeDialog: DemoUpdatableDialog? = null

    private val viewModel: SettingsViewModel by viewModels()
    private val connectionViewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    private val storageItemClickListener = object : IStorageItemClickListener {
        override fun onFormatClick(location: StorageData.FileLocation) {
            val name = CameraDemoDisplayLabels.storageFileLocation(requireContext(), location)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.storage_format_confirm_title)
                .setMessage(getString(R.string.storage_format_confirm_message, name))
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.format) { _, _ ->
                    viewModel.formatStorage(location, connectionViewModel.getCameraDevice())
                }
                .show()
        }

        override fun onSetMainStorageClick(location: StorageData.FileLocation) {
            viewModel.setMainStorage(location, connectionViewModel.getCameraDevice())
        }
    }

    private val storageAdapter = StorageListAdapter(storageItemClickListener)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerStorageList.adapter = storageAdapter

        setupStaticListeners()

        childFragmentManager.setFragmentResultListener(
            FirmwarePickBottomSheet.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val path = bundle.getString(FirmwarePickBottomSheet.RESULT_PATH)
                ?: return@setFragmentResultListener
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.upgradeFirmware(connectionViewModel.getCameraDevice(), path)
            }
        }

        childFragmentManager.setFragmentResultListener(
            RecentBleDevicePickBottomSheet.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            bundle.getString(RecentBleDevicePickBottomSheet.RESULT_SN)?.let(viewModel::wakeBySn)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { collectUiState() }
                launch { collectDemoLogExportState() }
                launch { collectCameraLogExportState() }
                launch { collectFirmwareUpgradeState() }
                launch { collectSettingsEffects() }
                launch { collectConnectionState() }
            }
        }
    }

    private fun setupStaticListeners() {
        binding.moduleLogHeader.setOnClickListener { viewModel.toggleModule("log") }
        binding.moduleWifiHeader.setOnClickListener { viewModel.toggleModule("wifi") }
        binding.moduleActivationHeader.setOnClickListener { viewModel.toggleModule("activation") }
        binding.btnWakePicker.setOnClickListener {
            // 空列表直接 toast 提示，不弹出 BottomSheet
            val list = DemoAppPreferences.readRecentBleDevices(requireContext())
            if (list.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.wake_no_recent_device,
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            RecentBleDevicePickBottomSheet.show(childFragmentManager)
        }
        binding.btnBleAuth.setOnClickListener {
            viewModel.requestBleAuthorization(connectionViewModel.getCameraDevice())
        }

        // ---- 日志管理 ----
        binding.switchSdkLog.setOnCheckedChangeListener { _, checked ->
            viewModel.setLogCaptureEnabled(checked)
        }
        binding.spinnerLogLevel.adapter =
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                LogLevel.entries.toList(),
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val initialLogOrd =
            DemoAppPreferences.readLogLevel(requireContext()).ordinal.coerceIn(0, LogLevel.entries.lastIndex)
        binding.spinnerLogLevel.setSelection(initialLogOrd, false)
        binding.spinnerLogLevel.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?,
                    v: View?,
                    pos: Int,
                    id: Long
                ) {
                    val level = LogLevel.entries.getOrNull(pos) ?: return
                    viewModel.setSdkLogLevel(level)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            },
        )
        binding.btnExportDemoLog.setOnClickListener {
            viewModel.exportAppLog()
        }
        binding.btnExportCameraLog.setOnClickListener {
            viewModel.exportCameraLog(connectionViewModel.getCameraDevice())
        }

        val w = binding.wifiSection
        // ---- Wi-Fi 信息 ----
        w.btnGetCountry.setOnClickListener {
            viewModel.fetchWifiChannels(connectionViewModel.getCameraDevice())
        }
        w.btnGetCurrentChannel.setOnClickListener {
            viewModel.fetchCurrentChannel(connectionViewModel.getCameraDevice())
        }

        w.btnSetCountry.setOnClickListener {
            val code = w.spinnerSetCountry.selectedItem as? String ?: ""
            viewModel.setCountryCode(connectionViewModel.getCameraDevice(), code)
        }

        w.btnSetChannel.setOnClickListener {
            val channel = w.spinnerWifiChannel.selectedItem as? Int
            if (channel != null) {
                viewModel.setWifiChannel(connectionViewModel.getCameraDevice(), channel)
            } else {
                showMessageDialog(getString(R.string.please_fetch_channels_first))
            }
        }

        w.btnWifiOn.setOnClickListener { viewModel.openWifi(connectionViewModel.getCameraDevice()) }
        w.btnWifiOff.setOnClickListener { viewModel.closeWifi(connectionViewModel.getCameraDevice()) }
        w.btnWifiRestart.setOnClickListener { viewModel.restartWifi(connectionViewModel.getCameraDevice()) }

        // ---- 激活 ----
        binding.btnActivate.setOnClickListener {
            val appId = binding.activationId.text?.toString()?.trim() ?: ""
            val secret = binding.activationSecret.text?.toString()?.trim() ?: ""
            viewModel.activateCamera(connectionViewModel.getCameraDevice(), appId, secret)
        }

        // ---- 设备控制 ----
        binding.switchMute.setOnCheckedChangeListener { _, c ->
            viewModel.switchMute(c, connectionViewModel.getCameraDevice())
        }
        binding.btnCameraLock.setOnClickListener {
            viewModel.toggleCameraLock(connectionViewModel.getCameraDevice())
        }
        binding.btnShutdown.setOnClickListener {
            viewModel.shutdown(connectionViewModel.getCameraDevice())
        }
        binding.btnCalibrateGyro.setOnClickListener {
            viewModel.calibrateGyro(connectionViewModel.getCameraDevice())
        }
        binding.btnFirmware.setOnClickListener { openFirmwarePickSheet() }
        binding.btnSharpness.setOnClickListener {
            viewModel.setSharpness(
                connectionViewModel.getCameraDevice(),
                binding.spinnerSharpness.selectedItemPosition,
            )
        }
        binding.btnEncoding.setOnClickListener {
            viewModel.setEncoding(
                connectionViewModel.getCameraDevice(),
                binding.spinnerEncoding.selectedItemPosition,
            )
        }
        binding.llFisheyeStitch.setOnClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_stitchFragment)
        }

    }

    private suspend fun collectUiState() {
        val countryCodes = resources.getStringArray(R.array.wifi_country_codes)

        viewModel.ui.collect { state ->
            binding.moduleLogContent.isVisible = state.expandedModuleIds.contains("log")
            binding.moduleWifiContent.isVisible = state.expandedModuleIds.contains("wifi")
            binding.moduleActivationContent.isVisible =
                state.expandedModuleIds.contains("activation")
            binding.arrowLog.rotation = if (state.expandedModuleIds.contains("log")) 0f else -90f
            binding.arrowWifi.rotation = if (state.expandedModuleIds.contains("wifi")) 0f else -90f
            binding.arrowActivation.rotation =
                if (state.expandedModuleIds.contains("activation")) 0f else -90f
            binding.btnWakePicker.isEnabled = !state.wakingUp
            binding.rowWakePicker.isVisible = !state.showBleAuth
            binding.rowBleAuth.isVisible = state.showBleAuth

            binding.switchSdkLog.setOnCheckedChangeListener(null)
            binding.switchSdkLog.isChecked = state.logCaptureEnabled
            binding.switchSdkLog.setOnCheckedChangeListener { _, c ->
                viewModel.setLogCaptureEnabled(c)
            }
            binding.textDumperStatus.setText(
                if (state.dumperRunning) {
                    R.string.demo_log_dumper_status_running
                } else {
                    R.string.demo_log_dumper_status_stopped
                },
            )
            binding.textDumperStatus.setTextColor(
                requireContext().getColor(
                    if (state.dumperRunning) R.color.demo_green else R.color.demo_error_red,
                ),
            )

            val w = binding.wifiSection

            w.wifiName.text = state.wifiName.ifBlank { "—" }
            w.wifiPassword.text = state.wifiPassword.ifBlank { "—" }
            w.wifiMac.text = state.wifiMac
            // MAC 地址行：仅在相机返回非空 MAC 时显示
            w.wifiMacRow.isVisible = state.wifiMac.isNotBlank()

            w.countryCode.text = state.countryCode
            w.currentChannel.text = state.currentChannel?.toString() ?: "—"

            val countryIdx = countryCodes.indexOf(state.countryCode)
            if (countryIdx >= 0 && w.spinnerSetCountry.selectedItemPosition != countryIdx) {
                w.spinnerSetCountry.setSelection(countryIdx, false)
            }

            if (state.channelOptions.isNotEmpty()) {
                w.spinnerWifiChannel.adapter =
                    ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        state.channelOptions,
                    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                state.currentChannel?.let { ch ->
                    val idx = state.channelOptions.indexOf(ch)
                    if (idx >= 0) w.spinnerWifiChannel.setSelection(idx, false)
                }
            }

            binding.switchMute.setOnCheckedChangeListener(null)
            binding.switchMute.isChecked = state.soundMuted
            binding.switchMute.setOnCheckedChangeListener { _, c ->
                viewModel.switchMute(c, connectionViewModel.getCameraDevice())
            }
            binding.btnCameraLock.text =
                if (state.cameraLocked) getString(R.string.unlock) else getString(R.string.lock_screen)

            val logOrd = state.logLevel.ordinal
            if (binding.spinnerLogLevel.selectedItemPosition != logOrd) {
                binding.spinnerLogLevel.setSelection(
                    logOrd.coerceIn(0, LogLevel.entries.size - 1),
                    false,
                )
            }

            val sharpnessLastIdx =
                resources.getStringArray(R.array.sharpness_levels).lastIndex.coerceAtLeast(0)
            val sharpnessSel = state.sharpnessIndex.coerceIn(0, sharpnessLastIdx)
            if (binding.spinnerSharpness.selectedItemPosition != sharpnessSel) {
                binding.spinnerSharpness.setSelection(sharpnessSel, false)
            }

            if (binding.spinnerEncoding.selectedItemPosition != state.encodingIndex) {
                binding.spinnerEncoding.setSelection(state.encodingIndex, false)
            }

            storageAdapter.submitList(state.storageDataList)
        }
    }

    private suspend fun collectDemoLogExportState() {
        viewModel.demoLogExport.collect { state ->
            when (state) {
                DemoLogExportState.Idle -> {
                    demoLogProgressDialog?.dismiss()
                    demoLogProgressDialog = null
                }

                is DemoLogExportState.Exporting -> {
                    if (demoLogProgressDialog == null) {
                        demoLogProgressDialog = DemoMessageDialog.showUpdatableDialog(
                            this,
                            getString(R.string.export_demo_log),
                            initialMessage = state.message,
                            cancelable = false,
                        )
                    }
                }
            }
        }
    }

    private suspend fun collectCameraLogExportState() {
        viewModel.cameraLogExport.collect { exp ->
            when (exp) {
                CameraLogExportState.Idle -> {
                    cameraLogProgressDialog?.dismiss()
                    cameraLogProgressDialog = null
                }

                is CameraLogExportState.Exporting -> {
                    if (cameraLogProgressDialog == null) {
                        cameraLogProgressDialog =
                            DemoMessageDialog.showUpdatableDialog(
                                this,
                                getString(R.string.exporting_camera_log_title),
                                initialMessage = exp.message,
                            )
                    }
                    cameraLogProgressDialog?.updateMessage(exp.message)
                }
            }
        }
    }

    private suspend fun collectFirmwareUpgradeState() {
        viewModel.firmwareUpgrade.collect { fw ->
            when (fw) {
                FirmwareUpgradeUiState.Idle -> {
                    firmwareUpgradeDialog?.dismiss()
                    firmwareUpgradeDialog = null
                }

                is FirmwareUpgradeUiState.Progress -> {
                    if (firmwareUpgradeDialog == null) {
                        firmwareUpgradeDialog =
                            DemoMessageDialog.showUpdatableDialog(
                                this,
                                getString(R.string.firmware_upgrade_progress_title),
                                initialMessage = fw.message,
                            )
                    }
                    firmwareUpgradeDialog?.updateMessage(fw.message)
                }
            }
        }
    }

    private suspend fun collectSettingsEffects() {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsUiEffect.ShowToast -> toast(effect.message)
                is SettingsUiEffect.ShowDialog -> showMessageDialog(effect.message)
                is SettingsUiEffect.ShareDemoLogZip -> {
                    val shareZip = effect.uri
                    val label = getString(R.string.export_demo_log)
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, shareZip)
                            clipData =
                                ClipData.newUri(requireContext().contentResolver, label, shareZip)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    val chooser =
                        Intent.createChooser(send, getString(R.string.demo_log_share_chooser_title))
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(chooser)
                }

                is SettingsUiEffect.ShareCameraLogFile -> {
                    val uri = effect.uri
                    val label = getString(R.string.export_camera_log)
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData =
                                ClipData.newUri(requireContext().contentResolver, label, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    val chooser =
                        Intent.createChooser(send, getString(R.string.camera_log_share_chooser_title))
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(chooser)
                }
            }
        }
    }

    private suspend fun collectConnectionState() {
        connectionViewModel.connectionUi.collect { connState ->
            DemoTopConnectionStatusBinder.bind(
                requireContext(),
                binding.layoutConnStatus.connectionStatusDot,
                binding.layoutConnStatus.connectionStatusText,
                connState,
            )
            if (connState.connectState is ConnectState.Connected) {
                val device = connectionViewModel.getCameraDevice()
                viewModel.loadCameraState(device)
                viewModel.fetchWifiInfo(device)
                viewModel.fetchWifiChannels(device)
                viewModel.setConnectedCameraType(connState.device?.cameraType)
            } else {
                viewModel.clearCameraStateOnDisconnected()
            }

            binding.btnExportCameraLog.isEnabled = connState.connectState == ConnectState.Connected && connState.connectionMethodLabel != ConnectType.BLE.name
            binding.btnFirmware.isEnabled = connState.connectState == ConnectState.Connected && connState.connectionMethodLabel != ConnectType.BLE.name
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Android 12 及以下先申请存储权限，便于 [MediaStore.Downloads] 列出已索引固件；
     * 未授权时仍可在弹层内用 SAF 从「下载」选文件。Android 13+ 直接打开弹层（SAF 不依赖存储权限）。
     */
    private fun openFirmwarePickSheet() {
        // API 33+ 使用分类媒体权限；以下使用 READ_EXTERNAL_STORAGE
        val storagePerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Permission.READ_MEDIA_IMAGES, Permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Permission.READ_EXTERNAL_STORAGE)
        }
        if (!XXPermissions.isGranted(requireContext(), *storagePerms)) {
            XXPermissions.with(requireActivity()).permission(*storagePerms)
                .request { _, _ ->
                    if (!XXPermissions.isGranted(requireContext(), *storagePerms)) {
                        showMessageDialog(getString(R.string.firmware_storage_permission_denied))
                    }
                    FirmwarePickBottomSheet.show(childFragmentManager)
                }
        } else {
            FirmwarePickBottomSheet.show(childFragmentManager)
        }
    }

    override fun onDestroyView() {
        cameraLogProgressDialog?.dismiss()
        cameraLogProgressDialog = null
        demoLogProgressDialog?.dismiss()
        demoLogProgressDialog = null
        firmwareUpgradeDialog?.dismiss()
        firmwareUpgradeDialog = null
        super.onDestroyView()
        _binding = null
    }
}
