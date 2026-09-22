package com.insta360.kmpsdk.demo.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.insta360.kmpsdk.demo.ext.dp

@SuppressLint("RtlHardcoded")
class DownloadingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val icon: ImageView
    private val tvProgress: TextView

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        setBackgroundColor("#99000000".toColorInt())
        isClickable = true

        // 下载图标
        icon = ImageView(context).apply {
            setImageResource(android.R.drawable.stat_sys_download)
            layoutParams = LayoutParams(60, 60, Gravity.CENTER)
        }

        // 进度文字
        tvProgress = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = 20f.dp
            }
            gravity = Gravity.CENTER_HORIZONTAL
        }

        addView(icon)
        addView(tvProgress)
        visibility = GONE
    }

    fun start(progress: String = "0.0%") {
        visibility = VISIBLE
        setProgress(progress)
        startJumpAnim()
    }

    fun stop() {
        visibility = GONE
        clearAnimation()
        icon.clearAnimation()
    }

    @SuppressLint("SetTextI18n")
    fun setProgress(progress: String) {
        tvProgress.text = progress
        invalidate()
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