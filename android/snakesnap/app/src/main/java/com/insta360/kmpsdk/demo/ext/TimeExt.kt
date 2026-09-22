package com.insta360.kmpsdk.demo.ext

import android.icu.text.SimpleDateFormat
import java.util.Locale


fun Long.durationFormat(): String {
    val sec = this / 1000
    val hour: Long = sec / 3600
    val minute: Long = sec % 3600 / 60
    val second: Long = sec % 3600 % 60

    return if (hour == 0L) String.format(Locale.getDefault(), "%02d:%02d", minute, second)
    else String.format(Locale.getDefault(), "%02d:%02d:%02d", hour, minute, second)
}

fun Long.timeFormat(pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
    return SimpleDateFormat(pattern, Locale.ENGLISH).format(this)
}

fun Long.dateFormat(pattern: String = "yyyy-MM-dd"): String {
    return SimpleDateFormat(pattern, Locale.ENGLISH).format(this)
}