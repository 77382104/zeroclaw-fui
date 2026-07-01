package com.sipeed.zeroclaw.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sipeed.zeroclaw.MainActivity
import com.sipeed.zeroclaw.StartupTrace
import com.sipeed.zeroclaw.ZeroClawApp
import com.sipeed.zeroclaw.runtime.RuntimeLaunchRequest
import com.sipeed.zeroclaw.util.HealthChecker
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.max

class ZeroClawService : Service() {
    companion object {
        private const val TAG = "ZeroClawService"
        private const val NOTIFICATION_ID = 1
        private const val BINARY_NAME = "zeroclaw"
        private const val DEFAULT_PORT = 42618
        private const val HOME_DIR_NAME = "zeroclaw"
        private const val FIXED_WORKSPACE_NAME = ".zeroclaw"
        private const val FIXED_WEB_DIST_DIR = "web/dist"
        private const val FIXED_RUNTIME_LOG_NAME = "zeroclaw.log"
        private const val RUNTIME_SETUP_MARKER_NAME = ".runtime-initialized"
        private const val BUNDLED_ASSET_ROOT = "runtime"
        private const val BUNDLED_ASSET_SEED_ROOT = "runtime-seed"
        private const val BUNDLED_WORKSPACE_SEED_DIR = "zeroclaw_seed"
        private const val LEGACY_HOME_PATH = "/data/coclaw"
        private const val PREF_NAME = "zeroclaw_prefs"
        private const val KEY_RUNTIME_TOKEN = "runtime_token"
        private const val ACTION_START = "com.sipeed.zeroclaw.action.ZEROCLAW_START"
        private const val ACTION_STOP = "com.sipeed.zeroclaw.action.ZEROCLAW_STOP"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_PUBLIC_MODE = "public_mode"
        private const val EXTRA_EXTRA_ARGS = "extra_args"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastLog = ""
            private set

        @Volatile
        var processId: Int = -1
            private set

        @Volatile
        private var bundledSeedSyncAttempted = false

        @Volatile
        private var lastRuntimeLogReadError: String = ""

        @Volatile
        private var lastStopAttemptedPid: Int = -1

        @Volatile
        private var lastStopAttemptAt: String = ""

        @Volatile
        private var lastStopSucceeded: Boolean? = null

        @Volatile
        private var lastStopDiagnostics: String = ""

        const val FIXED_HOME_PATH = "/data/local/tmp/zeroclaw"

        data class RuntimeProbeResult(
            val pid: Int,
            val source: String,
        )

        private data class KillRuntimeResult(
            val success: Boolean,
            val details: String,
        )

        private fun runCommandForDiagnostics(command: List<String>): Pair<Int, String> {
            return try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                Pair(exitCode, output)
            } catch (e: Exception) {
                Pair(-1, e.message ?: "Unknown error")
            }
        }

        fun start(context: Context, request: RuntimeLaunchRequest? = null) {
            val launchRequest = request ?: defaultLaunchRequest(context)
            val intent = Intent(context, ZeroClawService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ID, launchRequest.profileId)
                putExtra(EXTRA_HOST, launchRequest.host)
                putExtra(EXTRA_PORT, launchRequest.port)
                putExtra(EXTRA_PUBLIC_MODE, launchRequest.publicMode)
                putStringArrayListExtra(EXTRA_EXTRA_ARGS, ArrayList(launchRequest.extraArgs))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ZeroClawService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun getHomePath(context: Context): String {
            migrateLegacyHomeIfNeeded(context)
            val homeDir = File(context.filesDir, HOME_DIR_NAME)
            homeDir.mkdirs()
            syncBundledRuntimeIfMissing(context, homeDir)
            return homeDir.absolutePath
        }

        fun getWorkspacePath(context: Context): String {
            val workspace = File(getHomePath(context), FIXED_WORKSPACE_NAME)
            workspace.mkdirs()
            return workspace.absolutePath
        }

        fun getInstalledBinaryPath(context: Context): String {
            return File(getHomePath(context), BINARY_NAME).absolutePath
        }

        fun getConfigPath(context: Context): String {
            return File(getWorkspacePath(context), "config.toml").absolutePath
        }

        fun getWebDistPath(context: Context): String {
            return File(getHomePath(context), FIXED_WEB_DIST_DIR).absolutePath
        }

        fun getRuntimeLogPath(context: Context): String {
            return File(getHomePath(context), FIXED_RUNTIME_LOG_NAME).absolutePath
        }

        fun readRuntimeLogText(context: Context): String {
            val runtimeLog = File(getRuntimeLogPath(context))
            return if (!runtimeLog.exists()) {
                lastRuntimeLogReadError = "Log file does not exist: ${runtimeLog.absolutePath}"
                ""
            } else {
                try {
                    val text = runtimeLog.readText()
                    lastRuntimeLogReadError = if (text.isBlank()) {
                        "Log file is empty: ${runtimeLog.absolutePath}"
                    } else {
                        ""
                    }
                    text
                } catch (e: Exception) {
                    val error = "Failed to read ${runtimeLog.absolutePath}: ${e.message}"
                    lastRuntimeLogReadError = error
                    error
                }
            }
        }

        fun getRuntimeDiagnostics(context: Context): Map<String, Any?> {
            val homeDir = File(FIXED_HOME_PATH)
            val workspaceDir = File(homeDir, FIXED_WORKSPACE_NAME)
            val configFile = File(workspaceDir, "config.toml")
            val runtimeLog = File(homeDir, FIXED_RUNTIME_LOG_NAME)
            val activeProbe = probeExistingRuntime(context)

            return mapOf(
                "appUid" to android.os.Process.myUid(),
                "appPid" to android.os.Process.myPid(),
                "serviceRunning" to isRunning,
                "serviceTrackedPid" to processId,
                "detectedRuntimePid" to (activeProbe?.pid ?: -1),
                "detectedRuntimeSource" to (activeProbe?.source ?: ""),
                "homePath" to homeDir.absolutePath,
                "homeExists" to homeDir.exists(),
                "homeReadable" to homeDir.canRead(),
                "homeWritable" to homeDir.canWrite(),
                "workspacePath" to workspaceDir.absolutePath,
                "workspaceExists" to workspaceDir.exists(),
                "workspaceReadable" to workspaceDir.canRead(),
                "workspaceWritable" to workspaceDir.canWrite(),
                "configPath" to configFile.absolutePath,
                "configExists" to configFile.exists(),
                "configReadable" to configFile.canRead(),
                "runtimeLogPath" to runtimeLog.absolutePath,
                "runtimeLogExists" to runtimeLog.exists(),
                "runtimeLogReadable" to runtimeLog.canRead(),
                "runtimeLogWritable" to runtimeLog.canWrite(),
                "runtimeLogSizeBytes" to if (runtimeLog.exists()) runtimeLog.length() else 0L,
                "lastRuntimeLogReadError" to lastRuntimeLogReadError,
                "lastStopAttemptedPid" to lastStopAttemptedPid,
                "lastStopAttemptAt" to lastStopAttemptAt,
                "lastStopSucceeded" to lastStopSucceeded,
                "lastStopDiagnostics" to lastStopDiagnostics,
            )
        }

        fun readConfigText(context: Context): String {
            val config = File(getConfigPath(context))
            return if (config.exists()) config.readText() else ""
        }

        fun writeConfigText(context: Context, content: String) {
            val config = File(getConfigPath(context))
            config.parentFile?.mkdirs()
            config.writeText(content)
        }

        fun getDashboardUrl(): String = "http://127.0.0.1:$DEFAULT_PORT"

        fun getDashboardUrl(context: Context): String = "http://127.0.0.1:${getDashboardPort(context)}"

        fun getRuntimeToken(context: Context): String {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_RUNTIME_TOKEN, "")
            if (!existing.isNullOrBlank()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_RUNTIME_TOKEN, generated).apply()
            return generated
        }

        fun readCoreVersion(context: Context): String {
            return try {
                val binaryFile = resolveBinaryForInspection(context)
                val pb = ProcessBuilder(binaryFile.absolutePath, "self-test")
                    .directory(File(getWorkspacePath(context)))
                    .redirectErrorStream(true)
                pb.environment().putAll(buildEnvironment(context))
                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    output.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: "self-test ok"
                } else {
                    "unknown"
                }
            } catch (e: Exception) {
                Log.w(TAG, "readCoreVersion failed: ${e.message}", e)
                "unknown"
            }
        }

        fun getDashboardPort(context: Context): Int {
            return readGatewayPortFromConfig(context)
        }

        fun getServiceStatus(context: Context): Map<String, Any?> {
            val activeProbe = probeExistingRuntime(context)
            if (activeProbe != null) {
                isRunning = true
                if (processId <= 0) {
                    processId = activeProbe.pid
                }
                if (lastLog.isBlank()) {
                    lastLog = "Detected existing zeroclaw runtime via ${activeProbe.source} (PID: ${activeProbe.pid})"
                }
            } else {
                isRunning = false
                processId = -1
            }

            return mapOf(
                "isRunning" to isRunning,
                "pid" to processId,
                "lastLog" to lastLog,
                "configPath" to getConfigPath(context),
            ) + getRuntimeSetupStatus(context)
        }

        private fun defaultLaunchRequest(context: Context): RuntimeLaunchRequest {
            return RuntimeLaunchRequest(
                profileId = "zeroclaw",
                host = "0.0.0.0",
                port = getDashboardPort(context),
                publicMode = true,
                workspacePath = null,
                extraArgs = emptyList(),
                envOverrides = emptyMap(),
            )
        }

        private fun buildEnvironment(context: Context): Map<String, String> {
            val homeDir = File(getHomePath(context))
            homeDir.mkdirs()
            val workspace = File(getWorkspacePath(context))
            workspace.mkdirs()
            val tmpDir = File(context.cacheDir, "tmp")
            tmpDir.mkdirs()
            val configPath = File(workspace, "config.toml")

            return mapOf(
                "HOME" to homeDir.absolutePath,
                "ZEROCLAW_HOME" to homeDir.absolutePath,
                "ZEROCLAW_WORKSPACE" to FIXED_WORKSPACE_NAME,
                "ZEROCLAW_CONFIG" to configPath.absolutePath,
                "ZEROCLAW_TOKEN" to getRuntimeToken(context),
                "TMPDIR" to tmpDir.absolutePath,
                "PATH" to "/system/bin:/system/xbin",
                "LANG" to "en_US.UTF-8",
                "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            )
        }

        private fun resolveBinaryForInspection(context: Context): File {
            val installedBinary = File(getInstalledBinaryPath(context))
            if (installedBinary.exists()) return installedBinary
            return resolvePackagedBinarySource(context)
        }

        private fun resolveInstalledBinaryFile(context: Context): File {
            val installedBinary = File(getInstalledBinaryPath(context))
            if (installedBinary.exists()) return installedBinary
            throw IllegalStateException("ZeroClaw runtime is not initialized")
        }

        private fun resolvePackagedBinarySource(context: Context): File {
            val nativeBinary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
            if (nativeBinary.exists()) return nativeBinary

            val extractedFromAssets = extractBinaryFromAssets(
                context = context,
                binaryName = BINARY_NAME,
                outputFile = File(context.cacheDir, BINARY_NAME),
            )
            if (extractedFromAssets != null) return extractedFromAssets

            val extracted = extractBinaryFromApk(
                context = context,
                binaryName = BINARY_NAME,
                outputFile = File(context.cacheDir, BINARY_NAME),
            )
            if (extracted != null) return extracted

            throw IllegalStateException("ZeroClaw binary not found: $BINARY_NAME")
        }

        private fun extractBinaryFromAssets(
            context: Context,
            binaryName: String,
            outputFile: File,
        ): File? {
            return try {
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val candidatePaths = listOf(
                    "$BUNDLED_ASSET_ROOT/$abi/$binaryName",
                    "$BUNDLED_ASSET_ROOT/arm64-v8a/$binaryName",
                    "$BUNDLED_ASSET_ROOT/armeabi-v7a/$binaryName",
                )
                if (outputFile.exists() && outputFile.canExecute()) {
                    return outputFile
                }

                val assetPath = candidatePaths.firstOrNull { assetExists(context, it) } ?: return null
                outputFile.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                outputFile.setExecutable(true)
                outputFile
            } catch (e: Exception) {
                Log.w(TAG, "extractBinaryFromAssets failed: ${e.message}", e)
                null
            }
        }

        private fun extractBinaryFromApk(
            context: Context,
            binaryName: String,
            outputFile: File,
        ): File? {
            return try {
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val zipEntryPath = "lib/$abi/$binaryName"
                if (outputFile.exists() && outputFile.canExecute()) {
                    return outputFile
                }

                outputFile.parentFile?.mkdirs()

                ZipFile(context.applicationInfo.sourceDir).use { zipFile ->
                    val entry = zipFile.getEntry(zipEntryPath)
                        ?: zipFile.getEntry("lib/arm64-v8a/$binaryName")
                        ?: zipFile.getEntry("lib/armeabi-v7a/$binaryName")
                        ?: return null
                    zipFile.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                outputFile.setExecutable(true)
                outputFile
            } catch (e: Exception) {
                Log.w(TAG, "extractBinaryFromApk failed: ${e.message}", e)
                null
            }
        }

        private fun findBundledRuntimePrefix(zipFile: ZipFile): String? {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val candidatePrefixes = listOf(
                "lib/$abi/",
                "lib/arm64-v8a/",
                "lib/armeabi-v7a/",
            )
            return candidatePrefixes.firstOrNull { candidate ->
                zipFile.entries().asSequence().any { entry ->
                    entry.name == "${candidate}${BINARY_NAME}" ||
                        entry.name.startsWith("${candidate}${FIXED_WEB_DIST_DIR}/") ||
                        entry.name.startsWith("${candidate}${FIXED_WORKSPACE_NAME}/")
                }
            }
        }

        private fun findBundledRuntimeAssetPrefix(context: Context): String? {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val candidatePrefixes = listOf(
                "$BUNDLED_ASSET_ROOT/$abi",
                "$BUNDLED_ASSET_ROOT/arm64-v8a",
                "$BUNDLED_ASSET_ROOT/armeabi-v7a",
            )
            return candidatePrefixes.firstOrNull { candidate ->
                assetExists(context, "$candidate/$BINARY_NAME") ||
                    assetExists(context, "$candidate/$FIXED_WEB_DIST_DIR/index.html") ||
                    assetDirectoryHasEntries(context, "$candidate/$FIXED_WORKSPACE_NAME")
            }
        }

        private fun findBundledWorkspaceSeedAssetPrefix(context: Context): String? {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val candidatePrefixes = listOf(
                "$BUNDLED_ASSET_SEED_ROOT/$abi/$BUNDLED_WORKSPACE_SEED_DIR",
                "$BUNDLED_ASSET_SEED_ROOT/arm64-v8a/$BUNDLED_WORKSPACE_SEED_DIR",
                "$BUNDLED_ASSET_SEED_ROOT/armeabi-v7a/$BUNDLED_WORKSPACE_SEED_DIR",
            )
            return candidatePrefixes.firstOrNull { candidate ->
                assetExists(context, "$candidate/config.toml") ||
                    assetDirectoryHasEntries(context, "$candidate/workspace")
            }
        }

        private fun syncBundledRuntimeIfMissing(context: Context, homeDir: File) {
            if (bundledSeedSyncAttempted) {
                return
            }
            bundledSeedSyncAttempted = true
            try {
                val copiedFromAssets = copyBundledRuntimeFromAssets(
                    context = context,
                    homeDir = homeDir,
                    overwrite = false,
                )
                copyBundledWorkspaceSeedFromAssets(
                    context = context,
                    homeDir = homeDir,
                    overwrite = false,
                )
                if (!copiedFromAssets) {
                    copyBundledRuntimeFromApk(
                        context = context,
                        homeDir = homeDir,
                        overwrite = false,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncBundledRuntimeIfMissing failed: ${e.message}", e)
            }
        }

        private fun copyBundledRuntimeFromAssets(
            context: Context,
            homeDir: File,
            overwrite: Boolean,
        ): Boolean {
            return try {
                val prefix = findBundledRuntimeAssetPrefix(context) ?: return false
                copyAssetDirectoryRecursively(
                    context = context,
                    assetPath = prefix,
                    targetDir = homeDir,
                    overwrite = overwrite,
                )
            } catch (e: Exception) {
                Log.w(TAG, "copyBundledRuntimeFromAssets failed: ${e.message}", e)
                false
            }
        }

        private fun copyBundledWorkspaceSeedFromAssets(
            context: Context,
            homeDir: File,
            overwrite: Boolean,
        ): Boolean {
            return try {
                val prefix = findBundledWorkspaceSeedAssetPrefix(context) ?: return false
                copyAssetDirectoryRecursively(
                    context = context,
                    assetPath = prefix,
                    targetDir = File(homeDir, FIXED_WORKSPACE_NAME),
                    overwrite = overwrite,
                )
            } catch (e: Exception) {
                Log.w(TAG, "copyBundledWorkspaceSeedFromAssets failed: ${e.message}", e)
                false
            }
        }

        private fun copyAssetDirectoryRecursively(
            context: Context,
            assetPath: String,
            targetDir: File,
            overwrite: Boolean,
        ): Boolean {
            val childNames = context.assets.list(assetPath).orEmpty()
            if (childNames.isEmpty()) {
                if (!assetExists(context, assetPath)) {
                    return false
                }
                if (!overwrite && targetDir.exists()) {
                    return false
                }
                targetDir.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(targetDir).use { output ->
                        input.copyTo(output)
                    }
                }
                if (targetDir.name == BINARY_NAME) {
                    targetDir.setExecutable(true)
                }
                return true
            }

            var copiedAny = false
            targetDir.mkdirs()
            childNames.forEach { child ->
                val childAssetPath = "$assetPath/$child"
                val childTarget = File(targetDir, child)
                if (copyAssetDirectoryRecursively(context, childAssetPath, childTarget, overwrite)) {
                    copiedAny = true
                }
            }
            return copiedAny
        }

        private fun assetExists(context: Context, assetPath: String): Boolean {
            return try {
                context.assets.open(assetPath).close()
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun assetDirectoryHasEntries(context: Context, assetPath: String): Boolean {
            return try {
                context.assets.list(assetPath)?.isNotEmpty() == true
            } catch (_: Exception) {
                false
            }
        }

        private fun copyBundledRuntimeFromApk(
            context: Context,
            homeDir: File,
            overwrite: Boolean,
        ): Boolean {
            return try {
                ZipFile(context.applicationInfo.sourceDir).use { zipFile ->
                    val prefix = findBundledRuntimePrefix(zipFile) ?: return false
                    var copiedAny = false

                    zipFile.entries().asSequence().forEach { entry ->
                        if (!entry.name.startsWith(prefix)) {
                            return@forEach
                        }
                        val relativePath = entry.name.removePrefix(prefix)
                        if (relativePath.isEmpty()) {
                            return@forEach
                        }
                        val target = File(homeDir, relativePath)
                        if (entry.isDirectory) {
                            target.mkdirs()
                            return@forEach
                        }
                        if (!overwrite && target.exists()) {
                            return@forEach
                        }
                        target.parentFile?.mkdirs()
                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                        copiedAny = true
                        if (target.name == BINARY_NAME) {
                            target.setExecutable(true)
                        }
                    }
                    copiedAny
                }
            } catch (e: Exception) {
                Log.w(TAG, "copyBundledRuntimeFromApk failed: ${e.message}", e)
                false
            }
        }

        private fun initializeRuntimeFallback(context: Context): Map<String, Any?> {
            val homeDir = File(getHomePath(context))
            val binaryFile = File(getInstalledBinaryPath(context))
            val webDistDir = File(getWebDistPath(context))
            val markerFile = File(getRuntimeSetupMarkerPath(context))

            homeDir.mkdirs()
            File(getWorkspacePath(context)).mkdirs()

            if (!binaryFile.exists()) {
                val sourceBinary = resolvePackagedBinarySource(context)
                sourceBinary.copyTo(binaryFile, overwrite = true)
            }
            if (!binaryFile.canExecute()) {
                binaryFile.setExecutable(true)
            }

            val webDistReady = webDistDir.exists() &&
                webDistDir.isDirectory &&
                webDistDir.list()?.isNotEmpty() == true
            if (!webDistReady) {
                throw IllegalStateException("ZeroClaw bundled runtime seed is not available in APK")
            }

            ensureDefaultConfig(context)
            markerFile.writeText("initialized=true\n")
            return getRuntimeSetupStatus(context)
        }

        private fun getRuntimeSetupMarkerPath(context: Context): String {
            return File(getHomePath(context), RUNTIME_SETUP_MARKER_NAME).absolutePath
        }

        private fun ensureDefaultConfig(context: Context) {
            val config = File(getConfigPath(context))
            if (config.exists()) {
                return
            }
            config.parentFile?.mkdirs()
            config.writeText(
                """
                [runtime]
                profile = "zeroclaw"
                host = "0.0.0.0"
                public_mode = true

                [gateway]
                port = $DEFAULT_PORT
                """.trimIndent()
            )
        }

        private fun readGatewayPortFromConfig(context: Context): Int {
            val configFile = File(getConfigPath(context))
            if (!configFile.exists()) {
                return DEFAULT_PORT
            }

            return try {
                var inGatewaySection = false
                configFile.forEachLine { rawLine ->
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        return@forEachLine
                    }
                    if (trimmed == "[gateway]") {
                        inGatewaySection = true
                        return@forEachLine
                    }
                    if (trimmed.startsWith("[")) {
                        inGatewaySection = false
                        return@forEachLine
                    }
                    if (!inGatewaySection) {
                        return@forEachLine
                    }

                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size != 2) {
                        return@forEachLine
                    }
                    val key = parts[0].trim()
                    val value = parts[1].trim().trim('"', '\'')
                    if (key == "port") {
                        val parsed = value.toIntOrNull()
                        if (parsed != null) {
                            throw GatewayPortFound(parsed)
                        }
                    }
                }
                DEFAULT_PORT
            } catch (found: GatewayPortFound) {
                found.port
            } catch (_: Exception) {
                DEFAULT_PORT
            }
        }

        fun getRuntimeSetupStatus(context: Context): Map<String, Any?> {
            val homeDir = File(getHomePath(context))
            val binaryFile = File(getInstalledBinaryPath(context))
            val configFile = File(getConfigPath(context))
            val webDistDir = File(getWebDistPath(context))
            val markerFile = File(getRuntimeSetupMarkerPath(context))
            val isInitialized =
                homeDir.exists() &&
                binaryFile.exists() &&
                binaryFile.canExecute() &&
                configFile.exists() &&
                webDistDir.exists() &&
                webDistDir.isDirectory &&
                markerFile.exists()

            return mapOf(
                "homePath" to homeDir.absolutePath,
                "binaryDir" to homeDir.absolutePath,
                "binaryPath" to binaryFile.absolutePath,
                "webDistPath" to webDistDir.absolutePath,
                "configPath" to configFile.absolutePath,
                "markerPath" to markerFile.absolutePath,
                "isHomeReady" to homeDir.exists(),
                "isBinaryInstalled" to binaryFile.exists(),
                "isConfigReady" to configFile.exists(),
                "isWebDistReady" to (webDistDir.exists() && webDistDir.isDirectory),
                "isInitialized" to isInitialized,
            )
        }

        private fun logRuntimeSetupStatus(
            context: Context,
            stage: String,
        ): Map<String, Any?> {
            val status = getRuntimeSetupStatus(context)
            StartupTrace.mark(
                stage,
                buildString {
                    append("initialized=${status["isInitialized"]}")
                    append(", home=${status["isHomeReady"]}")
                    append(", binary=${status["isBinaryInstalled"]}")
                    append(", config=${status["isConfigReady"]}")
                    append(", webDist=${status["isWebDistReady"]}")
                    append(", homePath=${status["homePath"]}")
                    append(", binaryPath=${status["binaryPath"]}")
                    append(", configPath=${status["configPath"]}")
                    append(", webDistPath=${status["webDistPath"]}")
                    append(", markerPath=${status["markerPath"]}")
                }
            )
            return status
        }

        fun initializeRuntime(context: Context): Map<String, Any?> {
            StartupTrace.mark("ZeroClawService.initializeRuntime start")
            val homeDir = File(getHomePath(context))
            homeDir.mkdirs()
            homeDir.listFiles()?.forEach { child ->
                if (child.name == FIXED_RUNTIME_LOG_NAME) {
                    return@forEach
                }
                if (child.isDirectory) {
                    child.deleteRecursively()
                } else {
                    child.delete()
                }
            }

            val copied = copyBundledRuntimeFromApk(
                context = context,
                homeDir = homeDir,
                overwrite = true,
            ) || copyBundledRuntimeFromAssets(
                context = context,
                homeDir = homeDir,
                overwrite = true,
            )
            val copiedWorkspaceSeed = copyBundledWorkspaceSeedFromAssets(
                context = context,
                homeDir = homeDir,
                overwrite = true,
            )
            StartupTrace.mark(
                "ZeroClawService.initializeRuntime copy finished",
                "copied=$copied, workspaceSeed=$copiedWorkspaceSeed"
            )
            return if (copied) {
                val binaryFile = File(getInstalledBinaryPath(context))
                if (binaryFile.exists() && !binaryFile.canExecute()) {
                    binaryFile.setExecutable(true)
                }
                ensureDefaultConfig(context)
                val markerFile = File(getRuntimeSetupMarkerPath(context))
                markerFile.writeText("initialized=true\n")
                logRuntimeSetupStatus(
                    context,
                    "ZeroClawService.initializeRuntime post-copy status",
                ).also {
                    StartupTrace.mark(
                        "ZeroClawService.initializeRuntime success",
                        "binary=${it["binaryPath"]}"
                    )
                }
            } else {
                StartupTrace.mark("ZeroClawService.initializeRuntime fallback")
                initializeRuntimeFallback(context).also {
                    logRuntimeSetupStatus(
                        context,
                        "ZeroClawService.initializeRuntime fallback status",
                    )
                    StartupTrace.mark(
                        "ZeroClawService.initializeRuntime success",
                        "binary=${it["binaryPath"]}"
                    )
                }
            }
        }

        fun probeExistingRuntime(context: Context): RuntimeProbeResult? {
            // 先通过 health check 探测
            val health = try {
                HealthChecker("http://127.0.0.1:${getDashboardPort(context)}/health").check()
            } catch (_: Exception) {
                null
            }
            if (health?.isHealthy == true) {
                val pid = if (health.pid > 0) health.pid else findRuntimePid()
                if (pid > 0) {
                    return RuntimeProbeResult(pid, "health")
                }
            }

            // 再通过进程扫描探测
            val pid = findRuntimePid()
            return if (pid > 0) RuntimeProbeResult(pid, "process-scan") else null
        }

        private fun findRuntimePid(): Int {
            val candidates = listOf(
                listOf("pidof", BINARY_NAME),
                listOf("/system/bin/pidof", BINARY_NAME),
                listOf("toybox", "pidof", BINARY_NAME),
            )

            for (command in candidates) {
                val pid = runPidCommand(command)
                if (pid > 0) {
                    return pid
                }
            }

            return scanRuntimePidFromPs()
        }

        private fun runPidCommand(command: List<String>): Int {
            return try {
                val output = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
                output
                    .split(Regex("\\s+"))
                    .mapNotNull { it.toIntOrNull() }
                    .firstOrNull { isRuntimeProcess(it) }
                    ?: -1
            } catch (_: Exception) {
                -1
            }
        }

        private fun scanRuntimePidFromPs(): Int {
            val psCommands = listOf(
                listOf("ps", "-A"),
                listOf("/system/bin/ps", "-A"),
                listOf("toybox", "ps", "-A"),
            )

            for (command in psCommands) {
                val pid = runPsCommand(command)
                if (pid > 0) {
                    return pid
                }
            }

            return -1
        }

        private fun runPsCommand(command: List<String>): Int {
            return try {
                val output = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readLines()

                output.firstNotNullOfOrNull { line ->
                    val normalized = line.trim()
                    if (normalized.isEmpty() || normalized.startsWith("PID")) {
                        return@firstNotNullOfOrNull null
                    }
                    val columns = normalized.split(Regex("\\s+"))
                    columns
                        .mapNotNull { it.toIntOrNull() }
                        .firstOrNull { isRuntimeProcess(it) }
                } ?: -1
            } catch (_: Exception) {
                -1
            }
        }

        private fun isRuntimeProcess(pid: Int): Boolean {
            if (pid <= 0) return false
            return try {
                val cmdlineFile = File("/proc/$pid/cmdline")
                if (!cmdlineFile.exists() || !cmdlineFile.canRead()) {
                    return false
                }
                val cmdline = cmdlineFile.readBytes()
                    .toString(Charsets.UTF_8)
                    .replace('\u0000', ' ')
                    .trim()
                if (cmdline.isEmpty()) {
                    return false
                }
                val executable = cmdline.substringBefore(' ').substringAfterLast('/')
                executable == BINARY_NAME
            } catch (_: Exception) {
                false
            }
        }

        private fun killRuntimePid(pid: Int): KillRuntimeResult {
            if (pid <= 0) {
                return KillRuntimeResult(
                    success = false,
                    details = "Invalid PID: $pid"
                )
            }

            val terminateCommands = listOf(
                listOf("kill", "-TERM", pid.toString()),
                listOf("/system/bin/kill", "-TERM", pid.toString()),
                listOf("toybox", "kill", "-TERM", pid.toString()),
            )
            val diagnostics = mutableListOf<String>()
            diagnostics += "Attempting to stop runtime PID $pid"

            terminateCommands.forEach { command ->
                val (exitCode, output) = runCommandForDiagnostics(command)
                diagnostics += "TERM `${command.joinToString(" ")}` -> exit=$exitCode output=${output.ifBlank { "<empty>" }}"
            }

            repeat(10) {
                Thread.sleep(300)
                if (findRuntimePid() <= 0) {
                    return KillRuntimeResult(
                        success = true,
                        details = diagnostics.joinToString("\n"),
                    )
                }
            }

            val forceKillCommands = listOf(
                listOf("kill", "-KILL", pid.toString()),
                listOf("/system/bin/kill", "-KILL", pid.toString()),
                listOf("toybox", "kill", "-KILL", pid.toString()),
            )

            forceKillCommands.forEach { command ->
                val (exitCode, output) = runCommandForDiagnostics(command)
                diagnostics += "KILL `${command.joinToString(" ")}` -> exit=$exitCode output=${output.ifBlank { "<empty>" }}"
            }

            repeat(5) {
                Thread.sleep(200)
                if (findRuntimePid() <= 0) {
                    return KillRuntimeResult(
                        success = true,
                        details = diagnostics.joinToString("\n"),
                    )
                }
            }

            return KillRuntimeResult(
                success = false,
                details = diagnostics.joinToString("\n") + "\nFailed to terminate runtime PID $pid"
            )
        }

        private fun migrateLegacyHomeIfNeeded(context: Context) {
            val legacyHome = File(LEGACY_HOME_PATH)
            if (!legacyHome.exists() || !legacyHome.isDirectory) {
                return
            }

            val targetHome = File(context.filesDir, HOME_DIR_NAME)
            if (!targetHome.exists()) {
                targetHome.mkdirs()
            }

            val migrationMarker = File(targetHome, ".migrated-from-legacy")
            if (migrationMarker.exists()) {
                return
            }

            copyIfMissing(
                source = File(legacyHome, FIXED_RUNTIME_LOG_NAME),
                target = File(targetHome, FIXED_RUNTIME_LOG_NAME),
            )
            copyDirectoryIfMissing(
                source = File(legacyHome, "bin"),
                target = File(targetHome, "bin"),
            )
            copyDirectoryIfMissing(
                source = File(legacyHome, FIXED_WORKSPACE_NAME),
                target = File(targetHome, FIXED_WORKSPACE_NAME),
            )
            copyDirectoryIfMissing(
                source = File(legacyHome, FIXED_WEB_DIST_DIR),
                target = File(targetHome, FIXED_WEB_DIST_DIR),
            )
            copyIfMissing(
                source = File(legacyHome, RUNTIME_SETUP_MARKER_NAME),
                target = File(targetHome, RUNTIME_SETUP_MARKER_NAME),
            )
            copyIfMissing(
                source = File(legacyHome, "bin/$BINARY_NAME"),
                target = File(targetHome, BINARY_NAME),
            )
            migrationMarker.writeText("migrated=true\n")
        }

        private fun copyDirectoryIfMissing(source: File, target: File) {
            if (!source.exists() || !source.isDirectory || target.exists()) {
                return
            }
            source.copyRecursively(target, overwrite = false)
        }

        private fun copyIfMissing(source: File, target: File) {
            if (!source.exists() || target.exists()) {
                return
            }
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = false)
        }
    }

    private var process: Process? = null
    private var serviceThread: Thread? = null
    private var logThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val logBuffer = StringBuilder()
    private val serviceLock = Object()
    @Volatile private var stopped = false
    private var restartCount = 0
    private val maxRestartAttempts = 3
    private class GatewayPortFound(val port: Int) : RuntimeException()

    private fun appendRuntimeLogFileLine(line: String) {
        try {
            val logFile = File(getRuntimeLogPath(this))
            logFile.parentFile?.mkdirs()
            logFile.appendText("$line\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append runtime log file", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRuntime()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification("Starting..."))
                acquireWakeLock()
                persistLaunchState(intent)
                val request = RuntimeLaunchRequest(
                    profileId = intent?.getStringExtra(EXTRA_PROFILE_ID).orEmpty().ifBlank { "zeroclaw" },
                    host = intent?.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { "0.0.0.0" },
                    port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT,
                    publicMode = true,
                    workspacePath = null,
                    extraArgs = intent?.getStringArrayListExtra(EXTRA_EXTRA_ARGS)?.toList().orEmpty(),
                    envOverrides = emptyMap(),
                )
                startRuntime(request)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopRuntime()
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    private fun startRuntime(request: RuntimeLaunchRequest) {
        synchronized(serviceLock) {
            if (serviceThread?.isAlive == true || process?.isAlive == true) return
            val existingRuntime = probeExistingRuntime(this)
            if (existingRuntime != null) {
                isRunning = true
                processId = existingRuntime.pid
                lastLog = "Reusing existing zeroclaw runtime via ${existingRuntime.source} (PID: ${existingRuntime.pid})"
                updateNotification("Running (PID: $processId)")
                return
            }
            stopped = false
            restartCount = 0
            serviceThread = Thread {
                try {
                    StartupTrace.mark(
                        "ZeroClawService.startRuntime begin",
                        "port=${request.port}, public=${request.publicMode}"
                    )
                    val setupStatus = getRuntimeSetupStatus(this)
                    val isInitialized = setupStatus["isInitialized"] as? Boolean ?: false
                    if (!isInitialized) {
                        throw IllegalStateException("ZeroClaw runtime is not initialized")
                    }
                    val binary = resolveInstalledBinaryFile(this)
                    if (!binary.canExecute()) {
                        binary.setExecutable(true)
                    }
                    runRuntime(binary, request)
                } catch (e: Exception) {
                    if (!stopped) {
                        lastLog = "Error: ${e.message}"
                        appendRuntimeLogFileLine(lastLog)
                        updateNotification(lastLog)
                        StartupTrace.mark(
                            "ZeroClawService.startRuntime failed",
                            e.message ?: e.javaClass.simpleName
                        )
                        Log.e(TAG, "Failed to start ZeroClaw runtime", e)
                    }
                }
            }.also { it.start() }
        }
    }

    private fun persistLaunchState(intent: Intent?) = Unit

    private fun buildEnvironment(): Map<String, String> {
        val homeDir = File(getHomePath(this))
        homeDir.mkdirs()
        val workspace = File(getWorkspacePath(this))
        workspace.mkdirs()
        val tmpDir = File(cacheDir, "tmp")
        tmpDir.mkdirs()
        return mapOf(
            "HOME" to homeDir.absolutePath,
            "ZEROCLAW_HOME" to homeDir.absolutePath,
            "ZEROCLAW_WORKSPACE" to FIXED_WORKSPACE_NAME,
            "ZEROCLAW_CONFIG" to getConfigPath(this),
            "ZEROCLAW_TOKEN" to getRuntimeToken(this),
            "TMPDIR" to tmpDir.absolutePath,
            "PATH" to "/system/bin:/system/xbin",
            "LANG" to "en_US.UTF-8",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
        )
    }

    private fun runRuntime(binaryFile: File, request: RuntimeLaunchRequest) {
        if (stopped) return
        StartupTrace.mark(
            "ZeroClawService.runRuntime launching",
            "binary=${binaryFile.absolutePath}"
        )

        val workspace = File(getWorkspacePath(this))
        workspace.mkdirs()
        val configFile = File(workspace, "config.toml")

        if (!configFile.exists()) {
            configFile.writeText(
                """
                [runtime]
                profile = "${request.profileId}"
                host = "0.0.0.0"
                public_mode = true

                [gateway]
                port = ${request.port}
                """.trimIndent()
            )
        }

        val cmdList = mutableListOf(binaryFile.absolutePath, "daemon")

        val pb = ProcessBuilder(cmdList)
            .directory(workspace)
            .redirectErrorStream(true)
        pb.environment().putAll(buildEnvironment())
        pb.environment().putAll(request.envOverrides)

        appendRuntimeLogFileLine("Starting command: ${cmdList.joinToString(" ")}")
        appendRuntimeLogFileLine("Working directory: ${workspace.absolutePath}")

        val proc = pb.start()
        synchronized(serviceLock) {
            if (stopped) {
                proc.destroyForcibly()
                return
            }
            process = proc
            isRunning = true
        }

        processId = try {
            val pidField = proc.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(proc)
        } catch (_: Exception) {
            -1
        }

        updateNotification("Running (PID: $processId)")
        StartupTrace.mark("ZeroClawService.runRuntime started", "pid=$processId")

        logThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val logLine = line ?: continue
                    Log.d(TAG, logLine)
                    appendLog(logLine)
                    appendRuntimeLogFileLine(logLine)
                }
            } catch (e: Exception) {
                if (!stopped) {
                    Log.w(TAG, "Log reader interrupted", e)
                }
            }
        }, "zeroclaw-log-reader").apply {
            isDaemon = true
            start()
        }

        val exitCode = proc.waitFor()
        isRunning = false
        processId = -1
        try { logThread?.join(2000) } catch (_: InterruptedException) {}

        if (stopped) return

        val lastOutput = logBuffer.toString().takeLast(500)
        lastLog = "Process exited (code $exitCode)\n$lastOutput"
        appendRuntimeLogFileLine("Process exited with code $exitCode")
        updateNotification("Stopped (exit code $exitCode)")
        if (exitCode != 0) {
            restartCount++
            if (restartCount <= maxRestartAttempts) {
                appendRuntimeLogFileLine("Restarting runtime, attempt $restartCount of $maxRestartAttempts")
                Thread.sleep(5000)
                if (!stopped) runRuntime(binaryFile, request)
            } else {
                lastLog = "Service crashed $restartCount times, stopped retrying"
                appendRuntimeLogFileLine(lastLog)
                updateNotification("Error: too many restarts")
            }
        }
    }

    private fun stopRuntime() {
        val detectedPid = if (processId > 0) {
            processId
        } else {
            probeExistingRuntime(this)?.pid ?: -1
        }
        lastStopAttemptedPid = detectedPid
        lastStopAttemptAt = java.time.Instant.now().toString()
        lastStopSucceeded = null
        lastStopDiagnostics = if (detectedPid > 0) {
            "Stop requested for runtime PID $detectedPid"
        } else {
            "Stop requested but no runtime PID was detected."
        }

        synchronized(serviceLock) {
            stopped = true
            process?.let { proc ->
                try {
                    proc.destroy()
                    val thread = Thread {
                        try {
                            proc.waitFor()
                        } catch (_: InterruptedException) {
                        }
                    }
                    thread.start()
                    thread.join(2_000)
                    if (proc.isAlive) {
                        proc.destroyForcibly()
                        lastStopDiagnostics += "\nTracked Process was still alive after destroy(); destroyForcibly() was used."
                    } else {
                        lastStopDiagnostics += "\nTracked Process exited after destroy()."
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping ZeroClaw process", e)
                    lastStopDiagnostics += "\nTracked Process stop error: ${e.message}"
                }
            }
            process = null
            isRunning = false
            processId = -1
            logThread?.interrupt()
            logThread = null
        }
        serviceThread?.let {
            try {
                it.join(2_000)
            } catch (_: InterruptedException) {
            }
        }
        serviceThread = null
        restartCount = 0

        // 在后台线程中终止进程，避免阻塞主线程
        if (detectedPid > 0) {
            Thread {
                val killResult = killRuntimePid(detectedPid)
                lastStopSucceeded = killResult.success
                lastStopDiagnostics = buildString {
                    append(lastStopDiagnostics)
                    append("\n")
                    append(killResult.details)
                }
                if (!killResult.success) {
                    Log.w(TAG, "Failed to terminate zeroclaw runtime pid=$detectedPid")
                }
            }.start()
        } else {
            lastStopSucceeded = true
        }
    }

    private fun createNotification(status: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ZeroClawService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ZeroClawApp.CHANNEL_ID)
            .setContentTitle("ZeroClaw")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createNotification(status))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ZeroClaw::ServiceWakeLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    @Synchronized
    private fun appendLog(line: String) {
        logBuffer.appendLine(line)
        if (logBuffer.length > 64 * 1024) {
            logBuffer.delete(0, max(0, logBuffer.length - 64 * 1024))
        }
        lastLog = line
    }
}
