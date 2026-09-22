package com.insta360.kmpsdk.demo.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.widget.NestedScrollView

class GestureAwareNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    var gestureInterceptTarget: View? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val target = gestureInterceptTarget
        if (target != null && isTouchInsideView(ev, target)) {
            return false
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun isTouchInsideView(ev: MotionEvent, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val x = ev.rawX
        val y = ev.rawY
        return x >= loc[0] && x <= loc[0] + view.width &&
            y >= loc[1] && y <= loc[1] + view.height
    }
}
