package com.insta360.kmpsdk.demo.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val localDateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

const val KB = 1024
const val MB = 1024 * KB
const val GB = 1024 * MB

fun Long.formatLocalTime(): String = localDateTimeFormatter.format(Instant.ofEpochMilli(this))


fun Long.formatStorageSize(): String = when {
    this >= GB -> String.format(Locale.US, "%.2f GB", this.toDouble() / GB)
    this >= MB -> String.format(Locale.US, "%.2f MB", this.toDouble() / MB)
    this >= KB -> String.format(Locale.US, "%.2f KB", this.toDouble() / KB)
    else -> "$this B"
}

fun Long.formatDuration(): String {
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
}
