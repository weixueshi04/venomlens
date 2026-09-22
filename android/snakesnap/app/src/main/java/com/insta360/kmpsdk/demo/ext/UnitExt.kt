package com.insta360.kmpsdk.demo.ext

import java.util.Locale


fun Long.gb(): String {
    return String.format(Locale.ENGLISH, "%.2f", this / 1024f / 1024f / 1024f) + "GB"
}

fun Long.mb(): String {
    return String.format(Locale.ENGLISH, "%.2f", this / 1024f / 1024f) + "MB"
}

/** 将字节/秒的速率格式化为自适应单位（B/S、KB/S、MB/S）的字符串 */
fun Long.speedFormat(): String {
    val bytesPerSec = this.coerceAtLeast(0)
    return when {
        bytesPerSec >= 1024 * 1024 -> String.format(Locale.ENGLISH, "%.2f", bytesPerSec / 1024f / 1024f) + "MB/S"
        bytesPerSec >= 1024 -> String.format(Locale.ENGLISH, "%.2f", bytesPerSec / 1024f) + "KB/S"
        else -> "${bytesPerSec}B/S"
    }
}