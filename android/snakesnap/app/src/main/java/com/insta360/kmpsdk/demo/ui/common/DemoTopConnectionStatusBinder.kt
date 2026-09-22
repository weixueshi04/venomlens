package com.insta360.kmpsdk.demo.ui.common

import android.content.Context
import android.view.View
import android.widget.TextView
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ui.connection.ConnectState
import com.insta360.kmpsdk.demo.ui.connection.ConnectionPageUiState

object DemoTopConnectionStatusBinder {
    fun bind(
        context: Context,
        dot: View,
        label: TextView,
        state: ConnectionPageUiState,
    ) {
        when (state.connectState) {
            ConnectState.Connected -> {
                dot.setBackgroundResource(R.drawable.demo_connection_dot_green)
                val typeLabel = connectionTypeDisplay(context, state.connectionMethodLabel)
                label.text =
                    context.getString(R.string.connected_with_method, typeLabel)
            }
            ConnectState.Connecting -> {
                dot.setBackgroundResource(R.drawable.demo_connection_dot_gray)
                label.text = context.getString(R.string.top_status_connecting)
            }
            ConnectState.Idle -> {
                dot.setBackgroundResource(R.drawable.demo_connection_dot_gray)
                label.text = context.getString(R.string.not_connected)
            }
        }
    }

    private fun connectionTypeDisplay(
        context: Context,
        raw: String,
    ): String {
        if (raw.isBlank()) return "—"
        return when (raw.uppercase()) {
            "WIFI" -> context.getString(R.string.connect_type_wifi)
            "BLE" -> context.getString(R.string.connect_type_ble)
            "USB" -> context.getString(R.string.connect_type_usb)
            else -> raw
        }
    }
}
