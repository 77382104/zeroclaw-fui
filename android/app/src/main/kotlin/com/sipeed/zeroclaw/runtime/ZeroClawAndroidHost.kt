package com.sipeed.zeroclaw.runtime

import android.content.Context
import android.util.Log
import com.sipeed.zeroclaw.service.ZeroClawService

class ZeroClawAndroidHost(
    private val context: Context,
    private val profile: RuntimeProfile,
) {
    companion object {
        private const val TAG = "ZeroClawAndroidHost"
    }

    fun startService(request: RuntimeLaunchRequest) {
        ZeroClawService.start(
            context = context,
            request = request.copy(profileId = profile.id),
        )
    }

    fun stopService() {
        ZeroClawService.stop(context)
    }

    fun getServiceStatus(): Map<String, Any?> {
        return ZeroClawService.getServiceStatus(context) + mapOf(
            "profileId" to profile.id,
        )
    }

    fun getRuntimeSetupStatus(): Map<String, Any?> {
        return ZeroClawService.getRuntimeSetupStatus(context) + mapOf(
            "profileId" to profile.id,
        )
    }

    fun initializeRuntime(): Map<String, Any?> {
        return ZeroClawService.initializeRuntime(context) + mapOf(
            "profileId" to profile.id,
        )
    }

    fun checkHealth(): com.sipeed.zeroclaw.util.HealthChecker.HealthStatus {
        return com.sipeed.zeroclaw.util.HealthChecker(
            "http://127.0.0.1:${ZeroClawService.getDashboardPort(context)}/health"
        ).check()
    }

    fun getRuntimeInfo(): Map<String, Any?> {
        return mapOf(
            "profile" to getRuntimeProfile(),
            "workspacePath" to getWorkspacePath(),
            "version" to getCoreVersion(),
        )
    }

    fun getRuntimeProfile(): Map<String, Any?> {
        return mapOf(
            "id" to profile.id,
            "displayName" to profile.displayName,
            "configFormat" to profile.configFormat,
            "defaultHealthEndpoint" to profile.defaultHealthEndpoint,
            "defaultDashboardEndpoint" to profile.defaultDashboardEndpoint,
            "requiresPairing" to profile.requiresPairing,
            "supportsEmbeddedDashboard" to profile.supportsEmbeddedDashboard,
            "startupMode" to profile.startupMode,
            "binaryLayout" to profile.binaryLayout,
        )
    }

    fun getConfigDescriptor(): ConfigDescriptor {
        return ConfigDescriptor(
            path = ZeroClawService.getConfigPath(context),
            format = profile.configFormat,
            editableAsText = true,
            editableStructurally = false,
            schemaVersion = 1,
        )
    }

    fun readConfigText(): String = ZeroClawService.readConfigText(context)

    fun writeConfigText(content: String) {
        ZeroClawService.writeConfigText(context, content)
    }

    fun getDashboardInfo(): DashboardInfo {
        val dashboardUrl = ZeroClawService.getDashboardUrl(context)
        return DashboardInfo(
            url = dashboardUrl,
            supportsEmbeddedWebView = profile.supportsEmbeddedDashboard,
            requiresAuth = profile.requiresPairing,
            authMode = if (profile.requiresPairing) "token" else "none",
            token = ZeroClawService.getRuntimeToken(context),
            headers = emptyMap(),
        )
    }

    fun getWorkspacePath(): String = ZeroClawService.getWorkspacePath(context)

    fun getCoreVersion(): String = ZeroClawService.readCoreVersion(context)

    fun getConfigPath(): String = ZeroClawService.getConfigPath(context)

    fun getHomePath(): String = ZeroClawService.getHomePath(context)

    fun logRuntimeInfo() {
        Log.i(TAG, "Active profile: ${profile.id}")
    }
}
