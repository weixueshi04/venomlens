package com.insta360.kmpsdk.demo.ui.capture

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.insta360.kmpsdk.demo.R

/** 拍摄页共享 UI 小工具：BottomSheet 展开策略与镜头/模式 chip 渲染。 */
internal fun BottomSheetDialogFragment.configureCaptureBottomSheet() {
    val dialog = dialog as? BottomSheetDialog ?: return
    val bottomSheet =
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
    bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
    BottomSheetBehavior.from(bottomSheet).apply {
        peekHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt().coerceAtLeast(280)
        skipCollapsed = false
        isFitToContents = true
    }
}

/** 镜头/模式横向 chip 列表的统一渲染入口。 */
internal object CaptureChips {
    fun <T> render(
        container: LinearLayout,
        options: List<T>,
        selected: T?,
        textSizeSp: Float,
        label: (T) -> String,
        onClick: (T) -> Unit,
    ) {
        container.removeAllViews()
        options.forEach { option ->
            container.addView(
                buildChip(
                    container = container,
                    text = label(option),
                    textSizeSp = textSizeSp,
                    selected = option == selected,
                ).apply {
                    setOnClickListener { onClick(option) }
                },
            )
        }
    }

    /**
     * 模式列表选项不变时只刷新选中态，避免重建横向列表导致滚动位置跳动。
     * 返回本次已渲染的 options，调用方可保存用于下一次增量判断。
     */
    fun <T> renderStableModeStrip(
        scroll: HorizontalScrollView,
        container: LinearLayout,
        previousOptions: List<T>,
        options: List<T>,
        selected: T?,
        label: (T) -> String,
        onClick: (T) -> Unit,
    ): List<T> {
        if (options.isNotEmpty() && options == previousOptions && container.childCount == options.size) {
            options.forEachIndexed { index, option ->
                styleChip(container.getChildAt(index) as TextView, option == selected)
            }
            options.indexOf(selected).takeIf { it >= 0 }?.let {
                scrollIntoVisible(scroll, container, container.getChildAt(it))
            }
            return previousOptions
        }

        render(
            container = container,
            options = options,
            selected = selected,
            textSizeSp = 13f,
            label = label,
            onClick = onClick,
        )
        options.indexOf(selected).takeIf { it >= 0 }?.let {
            scrollIntoVisible(scroll, container, container.getChildAt(it))
        }
        return options
    }

    fun setInteractionLocked(
        container: LinearLayout,
        locked: Boolean,
    ) {
        val alpha = if (locked) 0.45f else 1f
        for (i in 0 until container.childCount) {
            container.getChildAt(i).apply {
                isClickable = !locked
                isFocusable = !locked
                this.alpha = alpha
            }
        }
    }

    private fun buildChip(
        container: LinearLayout,
        text: String,
        textSizeSp: Float,
        selected: Boolean,
    ): TextView {
        val density = container.resources.displayMetrics.density
        return TextView(container.context).apply {
            this.text = text
            textSize = textSizeSp
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
            isClickable = true
            isFocusable = true
            styleChip(this, selected)
        }
    }

    private fun styleChip(
        tv: TextView,
        selected: Boolean,
    ) {
        val textColor =
            if (selected) {
                ContextCompat.getColor(tv.context, R.color.white)
            } else {
                ContextCompat.getColor(tv.context, R.color.demo_primary_text)
            }
        tv.setBackgroundResource(if (selected) R.drawable.demo_chip_selected else R.drawable.demo_chip_bg)
        tv.setTextColor(textColor)
    }

    private fun scrollIntoVisible(
        scroll: HorizontalScrollView,
        content: LinearLayout,
        chip: View,
    ) {
        scroll.post {
            if (!scroll.isLaidOut || chip.parent !== content) return@post
            val innerWidth = scroll.width - scroll.paddingLeft - scroll.paddingRight
            if (innerWidth <= 0) return@post

            val visibleLeft = scroll.scrollX
            val visibleRight = visibleLeft + innerWidth
            val targetX =
                when {
                    chip.left < visibleLeft -> chip.left
                    chip.right > visibleRight -> chip.right - innerWidth
                    else -> return@post
                }.coerceIn(0, (content.width - innerWidth).coerceAtLeast(0))

            if (targetX != scroll.scrollX) {
                scroll.scrollTo(targetX, 0)
            }
        }
    }
}
