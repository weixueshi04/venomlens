package com.insta360.kmpsdk.demo.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.toColorInt

class DeletingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val icon: ImageView

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        setBackgroundColor("#99000000".toColorInt())
        isClickable = true

        // 删除图标
        icon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            layoutParams = LayoutParams(60, 60, Gravity.CENTER)
        }

        addView(icon)
        visibility = GONE
    }

    fun start() {
        visibility = VISIBLE
        startJumpAnim()
    }

    fun stop() {
        visibility = GONE
        icon.clearAnimation()
    }

    private fun startJumpAnim() {
        val anim = TranslateAnimation(0f, 0f, 0f, -25f).apply {
            duration = 500
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        icon.startAnimation(anim)
    }
}