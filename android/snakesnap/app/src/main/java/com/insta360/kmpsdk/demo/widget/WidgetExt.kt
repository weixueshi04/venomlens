package com.insta360.kmpsdk.demo.widget

import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * 给 RecyclerView 设置【用户下拉刷新】
 * 用户下拉 → 显示刷新动画 → 触发回调
 */
fun RecyclerView.enablePullToRefresh(onRefresh: () -> Unit): SwipeRefreshLayout {
    val refreshLayout = SwipeRefreshLayout(context)

    // 把当前 RecyclerView 移到 SwipeRefreshLayout 内部
    val parentView = parent as android.view.ViewGroup
    parentView.removeView(this)
    parentView.addView(refreshLayout, layoutParams)
    refreshLayout.addView(this)

    // 用户下拉时触发
    refreshLayout.setOnRefreshListener {
        onRefresh()
    }

    return refreshLayout
}

/** 结束刷新动画 */
fun SwipeRefreshLayout.finishRefresh() {
    isRefreshing = false
}