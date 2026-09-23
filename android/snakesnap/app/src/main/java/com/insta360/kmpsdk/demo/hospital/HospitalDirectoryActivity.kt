package com.insta360.kmpsdk.demo.hospital

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.ActivityHospitalDirectoryBinding
import com.insta360.kmpsdk.demo.databinding.ItemHospitalDirectoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HospitalDirectoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHospitalDirectoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHospitalDirectoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.hospitalBack.setOnClickListener { finish() }
        binding.hospitalEmergencyDial.setOnClickListener {
            openDialer(HospitalDirectory.MAINLAND_EMERGENCY_PHONE)
        }
        binding.hospitalEmergencyCopy.setOnClickListener {
            copyContact(
                getString(R.string.hospital_emergency_clip_label),
                HospitalDirectory.MAINLAND_EMERGENCY_PHONE,
                getString(R.string.hospital_emergency_copied),
            )
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                HospitalDirectory.load({
                    assets.open(HospitalDirectory.ASSET_NAME).bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                })
            }
            renderDirectory(result)
        }
    }

    internal fun renderDirectory(result: HospitalDirectoryResult) {
        binding.hospitalList.removeAllViews()
        binding.hospitalLoading.isVisible = false
        binding.hospitalEmpty.isVisible = false
        binding.hospitalDirectoryError.isVisible = false
        when (result) {
            is HospitalDirectoryResult.Failure -> {
                binding.hospitalDirectoryError.text = getString(
                    when (result.reason) {
                        HospitalDirectoryResult.Reason.READ -> R.string.hospital_read_error
                        HospitalDirectoryResult.Reason.FORMAT -> R.string.hospital_parse_error
                    },
                )
                binding.hospitalDirectoryError.isVisible = true
            }
            is HospitalDirectoryResult.Loaded -> {
                binding.hospitalEmpty.isVisible = result.hospitals.isEmpty()
                if (result.rejectedEntries > 0) {
                    binding.hospitalDirectoryError.text = getString(
                        R.string.hospital_validation_error, result.rejectedEntries,
                    )
                    binding.hospitalDirectoryError.isVisible = true
                }
                result.hospitals.forEach { hospital ->
                    val row = ItemHospitalDirectoryBinding.inflate(layoutInflater, binding.hospitalList, false)
                    row.root.tag = hospital.hospitalId
                    row.hospitalName.text = hospital.name
                    row.hospitalDetails.text = hospital.displayDetails
                    row.hospitalDial.contentDescription = getString(R.string.hospital_dial_description, hospital.name)
                    row.hospitalCopyAddress.contentDescription =
                        getString(R.string.hospital_copy_address_description, hospital.name)
                    row.hospitalCopyPhone.contentDescription =
                        getString(R.string.hospital_copy_phone_description, hospital.name)
                    row.hospitalDial.setOnClickListener { openDialer(hospital.phone) }
                    row.hospitalCopyAddress.setOnClickListener {
                        copyContact(getString(R.string.hospital_address_clip_label), hospital.address,
                            getString(R.string.hospital_address_copied))
                    }
                    row.hospitalCopyPhone.setOnClickListener {
                        copyContact(getString(R.string.hospital_phone_clip_label), hospital.phone,
                            getString(R.string.hospital_phone_copied))
                    }
                    binding.hospitalList.addView(row.root)
                }
            }
        }
    }

    private fun openDialer(phone: String) {
        try {
            // Do not pre-query packages: ACTION_DIAL works without queries or call permissions.
            startActivity(HospitalContactActions.dialIntent(phone))
            showActionMessage(getString(R.string.hospital_dial_opened))
        } catch (_: ActivityNotFoundException) {
            showActionMessage(getString(R.string.hospital_no_dialer))
        } catch (_: SecurityException) {
            showActionMessage(getString(R.string.hospital_dial_blocked))
        } catch (_: IllegalArgumentException) {
            showActionMessage(getString(R.string.hospital_invalid_phone))
        }
    }

    private fun copyContact(label: String, value: String, successMessage: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            showActionMessage(getString(R.string.hospital_copy_error))
            return
        }
        try {
            clipboard.setPrimaryClip(HospitalContactActions.sensitiveClip(label, value))
            showActionMessage(successMessage)
        } catch (_: SecurityException) {
            showActionMessage(getString(R.string.hospital_copy_error))
        }
    }

    private fun showActionMessage(message: String) {
        binding.hospitalActionMessage.text = message
        binding.hospitalActionMessage.isVisible = true
        // Bring errors/confirmation into view even when the tapped hospital is far down the list.
        binding.hospitalActionMessage.post {
            binding.root.smoothScrollTo(0, binding.hospitalActionMessage.top)
        }
    }
}

internal object HospitalContactActions {
    fun dialIntent(phone: String): Intent {
        val number = requireNotNull(HospitalDirectory.normalizedPhone(phone)) { "Invalid phone format" }
        return Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
    }

    fun sensitiveClip(label: String, value: String): ClipData = ClipData.newPlainText(label, value).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
}
