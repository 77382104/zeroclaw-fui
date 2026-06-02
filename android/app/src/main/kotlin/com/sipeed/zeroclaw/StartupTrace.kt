package com.sipeed.zeroclaw

import android.os.SystemClock
import android.util.Log

object StartupTrace {
    private const val TAG = "StartupTrace"
    private val processStartElapsedMs = SystemClock.elapsedRealtime()

    fun mark(label: String, details: String? = null) {
        val elapsedMs = SystemClock.elapsedRealtime() - processStartElapsedMs
        val suffix = if (details.isNullOrBlank()) "" else " | $details"
        Log.i(TAG, "[$elapsedMs" + "ms] $label$suffix")
    }
}
