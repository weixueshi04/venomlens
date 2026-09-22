package com.insta360.kmpsdk.demo.ui.common

import android.content.DialogInterface
import android.view.LayoutInflater
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.insta360.kmpsdk.demo.R

/**
 * 统一的消息弹窗，基于 MaterialAlertDialog，便于在 Fragment 中调用。
 */
object DemoMessageDialog {

    /**
     * 通用单按钮提示（可自定义标题；不传 [title] 时仅展示正文）。
     */
    fun show(
        fragment: Fragment,
        message: CharSequence,
        title: CharSequence? = null,
        @StringRes positiveButtonTextRes: Int = R.string.dialog_ok,
    ) {
        if (!fragment.isAdded) return
        val ctx = fragment.requireContext()
        val builder =
            MaterialAlertDialogBuilder(ctx)
                .setMessage(message)
                .setPositiveButton(positiveButtonTextRes, null)
        if (!title.isNullOrEmpty()) {
            builder.setTitle(title)
        }
        builder.show()
    }

    /**
     * 单按钮确认后执行 [onAcknowledge]（用于断连后返回连接页等）。
     */
    fun showAcknowledgeThen(
        fragment: Fragment,
        message: CharSequence,
        title: CharSequence? = null,
        @StringRes positiveButtonTextRes: Int = R.string.dialog_ok,
        onAcknowledge: () -> Unit,
    ) {
        if (!fragment.isAdded) return
        val ctx = fragment.requireContext()
        val builder =
            MaterialAlertDialogBuilder(ctx)
                .setMessage(message)
                .setPositiveButton(positiveButtonTextRes) { _, _ -> onAcknowledge() }
        if (!title.isNullOrEmpty()) {
            builder.setTitle(title)
        }
        builder.setCancelable(false)
        builder.show()
    }

    /**
     * 无按钮的阻塞式弹窗（导出等场景）。
     * 通过 [DemoUpdatableDialog.updateMessage] 刷新文案，结束时 [DemoUpdatableDialog.dismiss]。
     */
    fun showUpdatableDialog(
        fragment: Fragment,
        title: CharSequence? = null,
        initialMessage: CharSequence,
        cancelable: Boolean = false,
        onDismiss: (isUser: Boolean) -> Unit = {}
    ): DemoUpdatableDialog? {
        if (!fragment.isAdded) return null
        val ctx = fragment.requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_demo_progress, null)
        val messageView = view.findViewById<TextView>(R.id.progress_message)
        messageView.text = initialMessage
        val builder =
            MaterialAlertDialogBuilder(ctx)
                .setView(view)
                .setCancelable(cancelable)
        if (!title.isNullOrEmpty()) {
            builder.setTitle(title)
        }
        var isUserDismiss = false
        builder.setNegativeButton("取消") { p0, p1 ->
            isUserDismiss = true
            p0.dismiss()
        }
        val dialog = builder.create()
        dialog.show()
        dialog.setOnDismissListener {
            onDismiss(isUserDismiss)
        }
        return object : DemoUpdatableDialog {
            override fun updateMessage(message: CharSequence) {
                if (!dialog.isShowing) return
                val updateUi = Runnable {
                    if (!dialog.isShowing) return@Runnable
                    messageView.text = message
                }
                if (fragment.view != null) {
                    fragment.view?.post(updateUi)
                } else {
                    updateUi.run()
                }
            }

            override fun dismiss() {
                try {
                    if (dialog.isShowing) dialog.dismiss()
                } catch (_: Exception) {
                    // Activity 可能已销毁
                }
            }
        }
    }
}

/** 与 [DemoMessageDialog.showUpdatableDialog] 配套。 */
interface DemoUpdatableDialog {
    fun updateMessage(message: CharSequence)

    fun dismiss()
}

fun Fragment.showMessageDialog(message: CharSequence, title: CharSequence? = null) {
    DemoMessageDialog.show(this, message, title)
}
