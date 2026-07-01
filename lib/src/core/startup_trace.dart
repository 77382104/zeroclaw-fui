import 'dart:developer' as developer;

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

class StartupTrace {
  StartupTrace._();

  static final Stopwatch _stopwatch = Stopwatch()..start();
  static final Set<String> _onceMarkers = <String>{};

  static void mark(String label, {String? details}) {
    final elapsedMs = _stopwatch.elapsedMilliseconds;
    final suffix = details == null || details.isEmpty ? '' : ' | $details';
    final message = '[StartupTrace][${elapsedMs}ms] $label$suffix';
    developer.log(message, name: 'StartupTrace');
    debugPrint(message);
  }

  static void markOnce(String label, {String? details}) {
    if (_onceMarkers.add(label)) {
      mark(label, details: details);
    }
  }

  static void attachFirstFrameTrace() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      markOnce('Flutter first frame rasterized');
    });
  }
}
