package com.insta360.kmpsdk.demo.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.DialogFirmwarePickBottomSheetBinding
import com.insta360.kmpsdk.demo.util.copyUriToCacheFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 固件选择：优先展示 [MediaStore.Downloads] 中已索引的 .bin/.pkg；**「从下载或文件夹选择」** 使用 SAF，
 * 用户进入「下载」即可选任意 .bin（不依赖管理所有文件权限）。
 */
class FirmwarePickBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogFirmwarePickBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val adapter = FirmwareRowsAdapter { entry ->
        viewLifecycleOwner.lifecycleScope.launch {
            val path =
                withContext(Dispatchers.IO) {
                    copyUriToCacheFile(requireContext(), entry.contentUri)?.absolutePath
                }
            if (path != null) {
                deliverPath(path)
            } else {
                Toast.makeText(requireContext(), R.string.firmware_file_copy_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch {
                val path =
                    withContext(Dispatchers.IO) {
                        copyUriToCacheFile(requireContext(), uri)?.absolutePath
                    }
                if (path != null) {
                    deliverPath(path)
                } else {
                    Toast.makeText(requireContext(), R.string.firmware_file_copy_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogFirmwarePickBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.adapter = adapter
        binding.btnOpenDocument.setOnClickListener {
            openDocument.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/x-binary",
                    "application/x-msdownload",
                    "*/*",
                ),
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { queryDownloadFirmwarePackages(requireContext()) }
            adapter.submit(rows)
            binding.emptyIndexed.isVisible = rows.isEmpty()
        }
    }

    private fun deliverPath(absolutePath: String) {
        if (!absolutePath.endsWith(".bin", ignoreCase = true) &&
            !absolutePath.endsWith(".pkg", ignoreCase = true)
        ) {
            Toast.makeText(requireContext(), R.string.firmware_pick_require_bin_pkg, Toast.LENGTH_SHORT).show()
            return
        }
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_PATH to absolutePath),
        )
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class FirmwareRowsAdapter(
        private val onPick: (DownloadFirmwareEntry) -> Unit,
    ) : RecyclerView.Adapter<FirmwareRowsAdapter.Holder>() {

        private var items: List<DownloadFirmwareEntry> = emptyList()

        fun submit(list: List<DownloadFirmwareEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_row_download_firmware, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            holder.name.text = e.displayName
            holder.itemView.setOnClickListener { onPick(e) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(root: View) : RecyclerView.ViewHolder(root) {
            val name: TextView = root.findViewById(R.id.name)
        }
    }

    companion object {
        const val REQUEST_KEY = "firmware_pick_result"
        const val RESULT_PATH = "firmware_local_path"

        fun show(fm: androidx.fragment.app.FragmentManager) {
            FirmwarePickBottomSheet().show(fm, "FirmwarePickBottomSheet")
        }
    }
}
