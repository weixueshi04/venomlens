package com.insta360.kmpsdk.demo.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.databinding.LayoutLoadingBinding

class LoadingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : FrameLayout(context, attrs, defStyleAttr) {

    val binding: LayoutLoadingBinding = LayoutLoadingBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        binding.mask.setOnClickListener {

        }
    }

    fun setText(text: CharSequence) {
        binding.loadingMessage.text = text.ifEmpty { context.getString(R.string.camera_capture_blocking_busy) }
    }

}