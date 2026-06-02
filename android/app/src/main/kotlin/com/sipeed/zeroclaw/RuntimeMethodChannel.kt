package com.sipeed.zeroclaw

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.sipeed.zeroclaw.runtime.RuntimeLaunchRequest
import com.sipeed.zeroclaw.runtime.RuntimeRegistry
import com.sipeed.zeroclaw.service.ZeroClawService
import com.sipeed.zeroclaw.util.HealthChecker
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executor

class RuntimeMethodChannel(
    private val context: Context,
    flutterEngine: FlutterEngine
) {
    companion object {
        private const val TAG = "RuntimeMethodChannel"
        private const val CHANNEL_NAME = "com.sipeed.zeroclaw/zeroclaw"
        private const val PREF_NAME = "zeroclaw_prefs"
        private const val KEY_AUTO_START = "auto_start"
    }

    private val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
    private val host = RuntimeRegistry.getHost(context)
    private fun getMainExecutor(): Executor {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.mainExecutor
        } else {
            val handler = Handler(Looper.getMainLooper())
            Executor { r -> handler.post(r) }
        }
    }

    init {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    try {
                        StartupTrace.mark("MethodChannel.startService called")
                        val setupStatus = host.getRuntimeSetupStatus()
                        val isInitialized = setupStatus["isInitialized"] as? Boolean ?: false
                        if (!isInitialized) {
                            StartupTrace.mark(
                                "MethodChannel.startService blocked",
                                "runtime not initialized"
                            )
                            result.error(
                                "RUNTIME_NOT_INITIALIZED",
                                "ZeroClaw runtime is not initialized",
                                setupStatus,
                            )
                            return@setMethodCallHandler
                        }

                        val existingRuntime = ZeroClawService.probeExistingRuntime(context)
                        if (existingRuntime != null) {
                            Log.i(
                                TAG,
                                "ZeroClaw runtime already exists via ${existingRuntime.source}, skipping start"
                            )
                            StartupTrace.mark(
                                "MethodChannel.startService skipped",
                                "existing=${existingRuntime.source}:${existingRuntime.pid}"
                            )
                            result.success(true)
                            return@setMethodCallHandler
                        }

                        val request = RuntimeLaunchRequest(
                            profileId = RuntimeRegistry.getActiveProfile().id,
                            host = call.argument<String>("host") ?: "127.0.0.1",
                            port = call.argument<Int>("port") ?: ZeroClawService.getDashboardPort(context),
                            publicMode = false,
                            workspacePath = call.argument<String>("workspacePath"),
                            extraArgs = emptyList(),
                            envOverrides = emptyMap()
                        )
                        host.startService(request)
                        StartupTrace.mark(
                            "MethodChannel.startService dispatched",
                            "port=${request.port}, cmd=daemon"
                        )
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "startService failed", e)
                        StartupTrace.mark(
                            "MethodChannel.startService failed",
                            e.message ?: e.javaClass.simpleName
                        )
                        result.error("START_FAILED", e.message, host.getServiceStatus())
                    }
                }
                "stopService" -> {
                    try {
                        host.stopService()
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("STOP_FAILED", e.message, null)
                    }
                }
                "getRuntimeSetupStatus" -> result.success(host.getRuntimeSetupStatus())
                "initializeRuntime" -> {
                    try {
                        StartupTrace.mark("MethodChannel.initializeRuntime called")
                        val setup = host.initializeRuntime()
                        StartupTrace.mark(
                            "MethodChannel.initializeRuntime completed",
                            "initialized=${setup["isInitialized"]}"
                        )
                        result.success(setup)
                    } catch (e: Exception) {
                        Log.e(TAG, "initializeRuntime failed", e)
                        StartupTrace.mark(
                            "MethodChannel.initializeRuntime failed",
                            e.message ?: e.javaClass.simpleName
                        )
                        val details = host.getRuntimeSetupStatus().toMutableMap()
                        details["error"] = e.message ?: e.toString()
                        result.error("INITIALIZE_RUNTIME_FAILED", e.message, details)
                    }
                }
                "getServiceStatus" -> result.success(host.getServiceStatus())
                "checkHealth" -> {
                    Thread {
                        val mainExecutor = getMainExecutor()
                        try {
                            val status = HealthChecker(
                                "http://127.0.0.1:${ZeroClawService.getDashboardPort(context)}/health"
                            ).check()
                            mainExecutor.execute {
                                result.success(mapOf(
                                    "isHealthy" to status.isHealthy,
                                    "status" to status.status,
                                    "uptime" to status.uptime,
                                    "pid" to status.pid,
                                    "error" to (status.error ?: "")
                                ))
                            }
                        } catch (e: Exception) {
                            mainExecutor.execute {
                                result.error("HEALTH_CHECK_FAILED", e.message, null)
                            }
                        }
                    }.start()
                }
                "getConfig" -> result.success(host.readConfigText())
                "parseConfig" -> {
                    try {
                        val configText = host.readConfigText()
                        val parsed = parseConfigToml(configText)
                        result.success(parsed)
                    } catch (e: Exception) {
                        result.error("PARSE_CONFIG_FAILED", e.message, null)
                    }
                }
                "saveConfig" -> {
                    try {
                        val content = call.argument<String>("content") ?: ""
                        host.writeConfigText(content)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("SAVE_CONFIG_FAILED", e.message, null)
                    }
                }
                "getCoreVersion" -> {
                    Thread {
                        val mainExecutor = getMainExecutor()
                        try {
                            mainExecutor.execute { result.success(host.getCoreVersion()) }
                        } catch (e: Exception) {
                            Log.w(TAG, "getCoreVersion failed: ${e.message}", e)
                            mainExecutor.execute { result.success("unknown") }
                        }
                    }.start()
                }
                "getWorkspacePath" -> result.success(host.getWorkspacePath())
                "getHomePath" -> result.success(host.getHomePath())
                "getConfigPath" -> result.success(host.getConfigPath())
                "getFullLog" -> result.success(ZeroClawService.lastLog)
                "getRuntimeLogFileContent" -> {
                    try {
                        result.success(ZeroClawService.readRuntimeLogText(context))
                    } catch (e: Exception) {
                        result.error("GET_RUNTIME_LOG_FAILED", e.message, null)
                    }
                }
                "getRuntimeDiagnostics" -> {
                    try {
                        result.success(ZeroClawService.getRuntimeDiagnostics(context))
                    } catch (e: Exception) {
                        result.error("GET_RUNTIME_DIAGNOSTICS_FAILED", e.message, null)
                    }
                }
                "getDashboardInfo" -> result.success(host.getDashboardInfo().let {
                    mapOf(
                        "url" to it.url,
                        "supportsEmbeddedWebView" to it.supportsEmbeddedWebView,
                        "requiresAuth" to it.requiresAuth,
                        "authMode" to it.authMode,
                        "token" to it.token,
                        "headers" to it.headers,
                    )
                })
                "getRuntimeProfile" -> result.success(host.getRuntimeProfile())
                "getConfigDescriptor" -> result.success(host.getConfigDescriptor().let {
                    mapOf(
                        "path" to it.path,
                        "format" to it.format,
                        "editableAsText" to it.editableAsText,
                        "editableStructurally" to it.editableStructurally,
                        "schemaVersion" to it.schemaVersion,
                    )
                })
                "getAutoStart" -> {
                    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    result.success(prefs.getBoolean(KEY_AUTO_START, false))
                }
                "setAutoStart" -> {
                    try {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("SET_AUTO_START_FAILED", e.message, null)
                    }
                }
                "getRuntimeToken" -> result.success(ZeroClawService.getRuntimeToken(context))
                "getWebPort" -> result.success(ZeroClawService.getDashboardPort(context))
                "isStorageManagerGranted" -> {
                    val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Environment.isExternalStorageManager()
                    } else {
                        true
                    }
                    result.success(granted)
                }
                "requestStorageManager" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            result.success(true)
                        } catch (_: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            result.success(true)
                        }
                    } else {
                        result.success(true)
                    }
                }
                "getSafeDeviceInfo" -> {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val deviceCategory = if (context.resources.configuration.smallestScreenWidthDp >= 600) {
                        "Tablet"
                    } else {
                        "Mobile"
                    }
                    result.success(mapOf(
                        "deviceModel" to listOf(Build.MANUFACTURER, Build.MODEL)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .trim(),
                        "osVersion" to "Android ${Build.VERSION.RELEASE}",
                        "deviceCategory" to deviceCategory,
                        "appVersion" to (packageInfo.versionName ?: "unknown")
                    ))
                }
                "saveToDownloads" -> {
                    try {
                        val filename = call.argument<String>("filename") ?: "zeroclaw_logs.txt"
                        val bytes = call.argument<ByteArray>("bytes")
                        if (bytes == null) {
                            result.error("NO_BYTES", "No bytes provided", null)
                            return@setMethodCallHandler
                        }
                        result.success(saveToDownloads(filename, bytes))
                    } catch (e: Exception) {
                        result.error("SAVE_FAILED", e.message, null)
                    }
                }
                "copyContentUriToCache" -> {
                    try {
                        val uriStr = call.argument<String>("uri") ?: ""
                        val name = call.argument<String>("filename") ?: "zeroclaw_logs.txt"
                        result.success(copyContentUriToCache(uriStr, name))
                    } catch (e: Exception) {
                        result.error("COPY_FAILED", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    fun dispose() {
        channel.setMethodCallHandler(null)
    }

    /**
     * 简单解析 config.toml 的 [runtime] 和 [gateway] 部分
     * 返回 Map 包含：host, port, public_mode, profile
     */
    private fun parseConfigToml(content: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>(
            "host" to "127.0.0.1",
            "port" to 42618,
            "public_mode" to false,
            "profile" to "zeroclaw"
        )

        var inRuntimeSection = false
        var inGatewaySection = false
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue

            if (trimmed == "[runtime]") {
                inRuntimeSection = true
                inGatewaySection = false
                continue
            } else if (trimmed == "[gateway]") {
                inRuntimeSection = false
                inGatewaySection = true
                continue
            } else if (trimmed.startsWith("[")) {
                inRuntimeSection = false
                inGatewaySection = false
                continue
            }

            val parts = trimmed.split("=", limit = 2)
            if (parts.size != 2) continue

            val key = parts[0].trim()
            val value = parts[1].trim().trim('"', '\'')

            if (inRuntimeSection) {
                when (key) {
                    "host" -> result["host"] = value.ifEmpty { "127.0.0.1" }
                    "public_mode" -> result["public_mode"] = value.toBoolean()
                    "profile" -> result["profile"] = value.ifEmpty { "zeroclaw" }
                }
            } else if (inGatewaySection) {
                when (key) {
                    "port" -> result["port"] = value.toIntOrNull() ?: 42618
                }
            }
        }

        return result
    }

    private fun copyContentUriToCache(uriStr: String, fileName: String): String? {
        return try {
            if (uriStr.isBlank()) return null
            val uri = Uri.parse(uriStr)
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                val outFile = File(context.cacheDir, fileName)
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
                outFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "copyContentUriToCache failed: ${e.message}")
            null
        }
    }

    private fun saveToDownloads(fileName: String, data: ByteArray): String? {
        return try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: return null
            resolver.openOutputStream(uri).use { out ->
                out?.write(data)
                out?.flush()
            }
            uri.toString()
        } catch (e: Exception) {
            try {
                val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloads, "zeroclaw")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                f.writeBytes(data)
                f.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }
}
