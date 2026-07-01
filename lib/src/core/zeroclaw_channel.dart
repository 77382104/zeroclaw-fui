import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class ZeroClawDashboardInfo {
  const ZeroClawDashboardInfo({
    required this.url,
    required this.supportsEmbeddedWebView,
    required this.requiresAuth,
    required this.authMode,
    required this.token,
    required this.headers,
  });

  final String url;
  final bool supportsEmbeddedWebView;
  final bool requiresAuth;
  final String authMode;
  final String? token;
  final Map<String, String> headers;

  factory ZeroClawDashboardInfo.fromMap(Map<String, dynamic> map) {
    return ZeroClawDashboardInfo(
      url: map['url'] as String? ?? '',
      supportsEmbeddedWebView: map['supportsEmbeddedWebView'] as bool? ?? true,
      requiresAuth: map['requiresAuth'] as bool? ?? false,
      authMode: map['authMode'] as String? ?? 'none',
      token: map['token'] as String?,
      headers: Map<String, String>.from(
        (map['headers'] as Map?)?.map(
              (key, value) => MapEntry(key.toString(), value?.toString() ?? ''),
            ) ??
            const {},
      ),
    );
  }
}

/// ZeroClaw 原生 MethodChannel 客户端。
/// 仅在 Android 平台可用，用于与 Kotlin 原生服务层通信。
class ZeroClawChannel {
  static const _channel = MethodChannel('com.sipeed.zeroclaw/zeroclaw');

  /// 启动 ZeroClaw 前台服务
  static Future<bool> startService({int port = 42618, String args = ''}) async {
    final result = await _channel.invokeMethod<bool>('startService', {
      'port': port,
      'args': args,
    });
    return result ?? false;
  }

  /// 停止 ZeroClaw 前台服务
  static Future<bool> stopService() async {
    final result = await _channel.invokeMethod<bool>('stopService');
    return result ?? false;
  }

  /// 获取服务状态
  static Future<Map<String, dynamic>> getServiceStatus() async {
    final result = await _channel.invokeMethod<Map>('getServiceStatus');
    if (result == null) return {'isRunning': false, 'pid': -1, 'lastLog': ''};
    return Map<String, dynamic>.from(result);
  }

  static Future<Map<String, dynamic>> getRuntimeSetupStatus() async {
    final result = await _channel.invokeMethod<Map>('getRuntimeSetupStatus');
    return Map<String, dynamic>.from(result ?? const {});
  }

  static Future<Map<String, dynamic>> initializeRuntime() async {
    final result = await _channel.invokeMethod<Map>('initializeRuntime');
    return Map<String, dynamic>.from(result ?? const {});
  }

  /// 检查 /health 端点
  static Future<Map<String, dynamic>> checkHealth() async {
    final result = await _channel.invokeMethod<Map>('checkHealth');
    if (result == null) {
      return {'isHealthy': false, 'error': 'No response'};
    }
    return Map<String, dynamic>.from(result);
  }

  /// 读取 config.toml 内容
  static Future<String> getConfig() async {
    final result = await _channel.invokeMethod<String>('getConfig');
    return result ?? '';
  }

  /// 解析 config.toml 中的配置参数
  static Future<Map<String, dynamic>> parseConfig() async {
    final result = await _channel.invokeMethod<Map>('parseConfig');
    return Map<String, dynamic>.from(result ?? {});
  }

  /// 保存 config.toml 内容
  static Future<bool> saveConfig(String content) async {
    final result = await _channel.invokeMethod<bool>('saveConfig', {
      'content': content,
    });
    return result ?? false;
  }

  /// 获取完整日志
  static Future<String> getFullLog() async {
    final result = await _channel.invokeMethod<String>('getFullLog');
    return result ?? '';
  }

  /// 读取 ZeroClaw runtime 日志文件内容
  static Future<String> getRuntimeLogFileContent() async {
    final result = await _channel.invokeMethod<String>(
      'getRuntimeLogFileContent',
    );
    return result ?? '';
  }

  /// 获取 Android runtime 诊断信息
  static Future<Map<String, dynamic>> getRuntimeDiagnostics() async {
    final result = await _channel.invokeMethod<Map>('getRuntimeDiagnostics');
    return Map<String, dynamic>.from(result ?? const {});
  }

  /// 设置开机自启
  static Future<bool> setAutoStart(bool enabled) async {
    final result = await _channel.invokeMethod<bool>('setAutoStart', {
      'enabled': enabled,
    });
    return result ?? false;
  }

  /// 获取开机自启设置
  static Future<bool> getAutoStart() async {
    final result = await _channel.invokeMethod<bool>('getAutoStart');
    return result ?? false;
  }

  static Future<String> getCoreVersion() async {
    try {
      final result = await _channel.invokeMethod<String>('getCoreVersion');
      final value = result?.trim() ?? '';
      return value.isEmpty ? 'unknown' : value;
    } catch (_) {
      return 'unknown';
    }
  }

  /// 获取 config.toml 文件路径
  static Future<String> getConfigPath() async {
    final result = await _channel.invokeMethod<String>('getConfigPath');
    return result ?? '';
  }

  /// 获取 Runtime Channel token
  static Future<String> getRuntimeToken() async {
    final result = await _channel.invokeMethod<String>('getRuntimeToken');
    return result ?? '';
  }

  static Future<int> getWebPort() async {
    final result = await _channel.invokeMethod<int>('getWebPort');
    return result ?? 42618;
  }

  static Future<ZeroClawDashboardInfo> getDashboardInfo() async {
    final result = await _channel.invokeMethod<Map>('getDashboardInfo');
    return ZeroClawDashboardInfo.fromMap(
      Map<String, dynamic>.from(result ?? const {}),
    );
  }

  /// 获取安全的设备信息（避免敏感标识符）
  static Future<Map<String, String>> getSafeDeviceInfo() async {
    final result = await _channel.invokeMethod<Map>('getSafeDeviceInfo');
    if (result == null) return const {};
    return result.map(
      (key, value) => MapEntry(key.toString(), value?.toString() ?? ''),
    );
  }

  /// 检查存储权限是否已授予（Android 11+）
  static Future<bool> isStorageManagerGranted() async {
    final result = await _channel.invokeMethod<bool>('isStorageManagerGranted');
    return result ?? false;
  }

  /// 请求存储管理权限（Android 11+）
  static Future<bool> requestStorageManager() async {
    final result = await _channel.invokeMethod<bool>('requestStorageManager');
    return result ?? false;
  }
}
