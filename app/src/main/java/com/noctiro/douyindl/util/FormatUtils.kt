package com.noctiro.douyindl.util

import java.util.Locale

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "$bytes B"
    else String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

fun formatEta(seconds: Long): String {
    if (seconds < 0) return ""
    if (seconds < 60) return "${seconds}秒"
    val min = seconds / 60
    val sec = seconds % 60
    return if (min < 60) "${min}分${sec}秒"
    else "${min / 60}时${min % 60}分"
}
