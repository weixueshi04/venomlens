package com.insta360.kmpsdk.demo.ext

import kotlin.math.round


fun Double.roundTo(decimals: Int): Double {
    val d = decimals.coerceAtLeast(0)
    var factor = 1.0
    repeat(d) { factor *= 10.0 }
    return round(this * factor) / factor
}

fun Float.roundTo(decimals: Int): Double {
    val d = decimals.coerceAtLeast(0)
    var factor = 1.0
    repeat(d) { factor *= 10.0 }
    return round(this * factor) / factor
}