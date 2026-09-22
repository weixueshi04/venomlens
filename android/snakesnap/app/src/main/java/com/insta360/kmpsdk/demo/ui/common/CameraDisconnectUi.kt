package com.insta360.kmpsdk.demo.ui.common

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ui.connection.ConnectionViewModel
import kotlinx.coroutines.launch

/**
 * 仅用于**非底部导航**的 Fragment（如自连接页 Push 的拍摄页）。
 * 相机断开时弹窗提示，确认后回到底部导航的「连接」Tab。
 */
fun Fragment.observeCameraDisconnectedNavigateToConnection(connectionViewModel: ConnectionViewModel) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            connectionViewModel.cameraDisconnectedSignal.collect { event ->
                event ?: return@collect
                if (!isAdded) return@collect
                connectionViewModel.consumeCameraDisconnectedSignal(event.id)
                DemoMessageDialog.showAcknowledgeThen(
                    fragment = this@observeCameraDisconnectedNavigateToConnection,
                    message = getString(R.string.camera_disconnected_dialog_message),
                    title = getString(R.string.dialog_title_hint),
                ) {
                    navigateToConnectionTab()
                }
            }
        }
    }
}

private fun Fragment.navigateToConnectionTab() {
    val nav = findNavController()
    nav.popBackStack(R.id.connectionFragment, false)
    (requireActivity() as? AppCompatActivity)?.findViewById<BottomNavigationView>(R.id.bottom_nav)?.let { bottomNav ->
        if (bottomNav.menu.findItem(R.id.connectionFragment) != null) {
            bottomNav.selectedItemId = R.id.connectionFragment
        }
    }
}
