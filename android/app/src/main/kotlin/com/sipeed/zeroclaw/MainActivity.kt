package com.sipeed.zeroclaw

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private var methodChannel: RuntimeMethodChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.mark("MainActivity.onCreate start")
        super.onCreate(savedInstanceState)
        StartupTrace.mark("MainActivity.onCreate end")
        logIncomingIntent(intent)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        StartupTrace.mark("configureFlutterEngine start")
        super.configureFlutterEngine(flutterEngine)
        methodChannel = RuntimeMethodChannel(this, flutterEngine)
        StartupTrace.mark("configureFlutterEngine end")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        StartupTrace.mark("MainActivity.onResume")
        // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE 才能写 Downloads 目录。
        // 若未授予，跳转系统设置页引导用户开启（只弹一次，直到用户授予或主动拒绝）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            StartupTrace.mark("Storage permission missing, opening settings")
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        methodChannel?.dispose()
        methodChannel = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun logIncomingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == BuildConfig.ZEROCLAW_UMENG_LINK_SCHEME) {
            Log.i(TAG, "Received Umeng link: $data")
        }
    }
}
