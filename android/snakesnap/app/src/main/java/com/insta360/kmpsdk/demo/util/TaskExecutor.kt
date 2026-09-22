package com.insta360.kmpsdk.demo.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 最多同时执行 10 个 IO 任务
 * 超过的任务会自动排队
 */
object TaskExecutor {
    // 核心：固定 10 个线程的线程池
    private val executor = ThreadPoolExecutor(
        10,          // 核心线程：10
        10,          // 最大线程：10
        60L,         // 空闲时间
        TimeUnit.SECONDS,
        LinkedBlockingQueue() // 任务队列：无限排队
    )

    // 转换成 Kotlin 协程调度器
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
}