package com.sipeed.zeroclaw.util

import com.sipeed.zeroclaw.runtime.RuntimeProfile
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 轮询 ZeroClaw /health 端点检查服务状态。
 * 使用 HttpURLConnection 避免额外依赖。
 */
class HealthChecker(
    private val endpoint: String = "http://127.0.0.1:42618/health"
) {
    data class HealthStatus(
        val isHealthy: Boolean,
        val status: String = "unknown",
        val uptime: String = "",
        val pid: Int = -1,
        val error: String? = null
    )

    companion object {
        fun fromProfile(profile: RuntimeProfile): HealthChecker {
            return HealthChecker(profile.defaultHealthEndpoint)
        }
    }

    fun check(): HealthStatus {
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"

            try {
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    HealthStatus(
                        isHealthy = true,
                        status = json.optString("status", "ok"),
                        uptime = json.optString("uptime", ""),
                        pid = json.optInt("pid", -1)
                    )
                } else {
                    HealthStatus(
                        isHealthy = false,
                        error = "HTTP $code"
                    )
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            HealthStatus(
                isHealthy = false,
                error = e.message
            )
        }
    }
}
