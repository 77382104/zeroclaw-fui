import 'dart:io';

import 'core_service_adapter.dart';
import 'android_core_service_adapter.dart';
import 'desktop_core_service_adapter.dart';

class CoreServiceAdapterFactory {
  static CoreServiceAdapter create({
    String? binaryName,
    int port = 42618,
    String? configuredPath,
  }) {
    if (Platform.isAndroid) {
      return AndroidCoreServiceAdapter();
    }
    return DesktopCoreServiceAdapter(
      binaryName:
          binaryName ?? (Platform.isWindows ? 'zeroclaw.exe' : 'zeroclaw'),
      port: port,
      configuredPath: configuredPath,
    );
  }
}
