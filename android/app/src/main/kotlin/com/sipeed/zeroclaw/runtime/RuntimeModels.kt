package com.sipeed.zeroclaw.runtime

data class RuntimeProfile(
    val id: String,
    val displayName: String,
    val configFormat: String,
    val defaultHealthEndpoint: String,
    val defaultDashboardEndpoint: String,
    val requiresPairing: Boolean,
    val supportsEmbeddedDashboard: Boolean,
    val startupMode: String,
    val binaryLayout: String,
)

data class RuntimeLaunchRequest(
    val profileId: String,
    val host: String,
    val port: Int,
    val publicMode: Boolean,
    val workspacePath: String?,
    val extraArgs: List<String>,
    val envOverrides: Map<String, String>,
)

data class DashboardInfo(
    val url: String?,
    val supportsEmbeddedWebView: Boolean,
    val requiresAuth: Boolean,
    val authMode: String,
    val token: String?,
    val headers: Map<String, String>,
)

data class ConfigDescriptor(
    val path: String,
    val format: String,
    val editableAsText: Boolean,
    val editableStructurally: Boolean,
    val schemaVersion: Int,
)

