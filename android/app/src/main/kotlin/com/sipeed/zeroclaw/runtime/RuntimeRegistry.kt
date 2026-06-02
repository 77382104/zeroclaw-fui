package com.sipeed.zeroclaw.runtime

import android.content.Context

object RuntimeRegistry {
    private val zeroClawProfile = RuntimeProfile(
        id = "zeroclaw",
        displayName = "ZeroClaw",
        configFormat = "toml",
        defaultHealthEndpoint = "http://127.0.0.1:42618/health",
        defaultDashboardEndpoint = "http://127.0.0.1:42618",
        requiresPairing = true,
        supportsEmbeddedDashboard = true,
        startupMode = "daemon",
        binaryLayout = "executable",
    )

    fun getActiveProfile(): RuntimeProfile = zeroClawProfile

    fun getHost(context: Context): ZeroClawAndroidHost = ZeroClawAndroidHost(context, zeroClawProfile)
}

