package com.noctiro.douyindl.util

import android.content.Context
import com.noctiro.douyindl.R
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

fun formatEta(context: Context, seconds: Long): String {
    if (seconds < 0) return ""
    if (seconds < 60) return context.getString(R.string.eta_seconds, seconds.toInt())
    val min = seconds / 60
    val sec = seconds % 60
    return if (min < 60) context.getString(R.string.eta_minutes_seconds, min.toInt(), sec.toInt())
    else context.getString(R.string.eta_hours_minutes, (min / 60).toInt(), (min % 60).toInt())
}
