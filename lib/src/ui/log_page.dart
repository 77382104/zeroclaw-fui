import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:zeroclaw_flutter_ui/src/core/service_manager.dart';
import 'package:zeroclaw_flutter_ui/src/core/zeroclaw_channel.dart';
import 'package:zeroclaw_flutter_ui/src/core/ui_constants.dart';
import 'package:zeroclaw_flutter_ui/src/generated/l10n/app_localizations.dart';
import 'package:zeroclaw_flutter_ui/src/ui/widgets/tv_focusable.dart';

class LogPage extends StatefulWidget {
  const LogPage({super.key});

  @override
  State<LogPage> createState() => _LogPageState();
}

class _LogPageState extends State<LogPage> {
  final ScrollController _scrollController = ScrollController();
  bool _shouldAutoScroll = true;
  final FocusNode _logFocusNode = FocusNode();
  Timer? _runtimeLogRefreshTimer;
  List<String> _runtimeLogs = const [];
  Map<String, dynamic> _runtimeDiagnostics = const {};
  bool _isRuntimeLogLoading = false;
  _LogView _activeView = _LogView.app;

  @override
  void initState() {
    super.initState();
    _refreshRuntimeLogs(showLoading: true);
    _runtimeLogRefreshTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => _refreshRuntimeLogs(),
    );
    _scrollController.addListener(() {
      if (_scrollController.hasClients) {
        final position = _scrollController.position;
        if (position.pixels < position.maxScrollExtent - 50) {
          if (_shouldAutoScroll) setState(() => _shouldAutoScroll = false);
        } else {
          if (!_shouldAutoScroll) setState(() => _shouldAutoScroll = true);
        }
      }
    });
  }

  @override
  void dispose() {
    _runtimeLogRefreshTimer?.cancel();
    _scrollController.dispose();
    _logFocusNode.dispose();
    super.dispose();
  }

  void _scrollToBottom() {
    if (_shouldAutoScroll && _scrollController.hasClients) {
      _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
    }
  }

  bool _handleKeyEvent(KeyEvent event) {
    if (event is KeyDownEvent) {
      if (event.logicalKey == LogicalKeyboardKey.arrowUp) {
        if (_scrollController.hasClients) {
          final newOffset = (_scrollController.offset - 100).clamp(
            0.0,
            _scrollController.position.maxScrollExtent,
          );
          _scrollController.animateTo(
            newOffset,
            duration: const Duration(milliseconds: 200),
            curve: Curves.easeOut,
          );
          return true;
        }
      } else if (event.logicalKey == LogicalKeyboardKey.arrowDown) {
        if (_scrollController.hasClients) {
          final newOffset = (_scrollController.offset + 100).clamp(
            0.0,
            _scrollController.position.maxScrollExtent,
          );
          _scrollController.animateTo(
            newOffset,
            duration: const Duration(milliseconds: 200),
            curve: Curves.easeOut,
          );
          return true;
        }
      } else if (event.logicalKey == LogicalKeyboardKey.pageUp) {
        if (_scrollController.hasClients) {
          final newOffset = (_scrollController.offset - 300).clamp(
            0.0,
            _scrollController.position.maxScrollExtent,
          );
          _scrollController.animateTo(
            newOffset,
            duration: const Duration(milliseconds: 300),
            curve: Curves.easeOut,
          );
          return true;
        }
      } else if (event.logicalKey == LogicalKeyboardKey.pageDown) {
        if (_scrollController.hasClients) {
          final newOffset = (_scrollController.offset + 300).clamp(
            0.0,
            _scrollController.position.maxScrollExtent,
          );
          _scrollController.animateTo(
            newOffset,
            duration: const Duration(milliseconds: 300),
            curve: Curves.easeOut,
          );
          return true;
        }
      }
    }
    return false;
  }

  Future<void> _refreshRuntimeLogs({bool showLoading = false}) async {
    if (!Platform.isAndroid || _isRuntimeLogLoading) return;

    if (showLoading && mounted) {
      setState(() => _isRuntimeLogLoading = true);
    } else {
      _isRuntimeLogLoading = true;
    }

    try {
      final content = await ZeroClawChannel.getRuntimeLogFileContent();
      final diagnostics = await ZeroClawChannel.getRuntimeDiagnostics();
      final lines = content
          .split(RegExp(r'[\r\n]+'))
          .where((line) => line.trim().isNotEmpty)
          .toList(growable: false);
      if (!mounted) return;
      final diagnosticsChanged = !_mapEquals(
        _runtimeDiagnostics,
        diagnostics,
      );
      if (!_listEquals(_runtimeLogs, lines) ||
          diagnosticsChanged ||
          _isRuntimeLogLoading) {
        setState(() {
          _runtimeLogs = lines;
          _runtimeDiagnostics = diagnostics;
          _isRuntimeLogLoading = false;
        });
      } else {
        _isRuntimeLogLoading = false;
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _runtimeLogs = const [];
        _runtimeDiagnostics = const {};
        _isRuntimeLogLoading = false;
      });
    }
  }

  bool _listEquals(List<String> left, List<String> right) {
    if (identical(left, right)) return true;
    if (left.length != right.length) return false;
    for (var index = 0; index < left.length; index++) {
      if (left[index] != right[index]) return false;
    }
    return true;
  }

  bool _mapEquals(Map<String, dynamic> left, Map<String, dynamic> right) {
    if (identical(left, right)) return true;
    if (left.length != right.length) return false;
    for (final entry in left.entries) {
      if (!right.containsKey(entry.key)) return false;
      if (right[entry.key]?.toString() != entry.value?.toString()) return false;
    }
    return true;
  }

  @override
  Widget build(BuildContext context) {
    final appLogs = context.select<ServiceManager, List<String>>((s) => s.logs);
    final l10n = AppLocalizations.of(context)!;
    final colorScheme = Theme.of(context).colorScheme;
    final logs = _activeView == _LogView.app ? appLogs : _runtimeLogs;
    final title = _activeView == _LogView.app ? l10n.logs : 'ZeroClaw Logs';

    // 检测是否需要 TV/焦点导航模式
    // TV平台: Android TV 使用遥控器导航
    // 桌面平台: Windows/macOS/Linux 使用键盘导航
    // 移动端: Android手机/平板、iOS 使用触摸
    final useFocusMode = kIsTvFocusMode;

    WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToBottom());

    Widget logContent = ListView.builder(
      controller: _scrollController,
      itemCount: logs.length,
      addAutomaticKeepAlives: false,
      addRepaintBoundaries: true,
      itemBuilder: (context, index) => RepaintBoundary(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 2.0),
          child: Text(
            logs[index],
            style: TextStyle(
              fontFamily: GoogleFonts.firaCode().fontFamily,
              fontSize: 13,
              color: colorScheme.onSurfaceVariant,
              height: 1.5,
            ),
          ),
        ),
      ),
    );

    if (useFocusMode) {
      // 焦点导航模式（TV/桌面）：不使用 SelectionArea，使用 Focus 和 KeyboardListener 处理
      logContent = Focus(
        focusNode: _logFocusNode,
        autofocus: true,
        child: KeyboardListener(
          focusNode: FocusNode(),
          onKeyEvent: _handleKeyEvent,
          child: logContent,
        ),
      );
    } else {
      // 触摸模式（移动端）：使用 SelectionArea 支持文本选择
      logContent = SelectionArea(child: logContent);
    }

    return Scaffold(
      backgroundColor: colorScheme.surface,
      appBar: AppBar(
        backgroundColor: colorScheme.surface,
        elevation: 0,
        centerTitle: false,
        title: Text(
          title.toUpperCase(),
          style: GoogleFonts.inter(
            fontWeight: FontWeight.w800,
            fontSize: 20,
            letterSpacing: 1.0,
          ),
        ),
        actions: [
          TextButton.icon(
            style: TextButton.styleFrom(foregroundColor: colorScheme.onSurface),
            onPressed: _exportLogs,
            icon: Icon(Icons.download, size: 18, color: colorScheme.onSurface),
            label: Text(
              l10n.exportLogs,
              style: TextStyle(color: colorScheme.onSurface),
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(right: 16.0),
            child: Center(
              child: Text(
                l10n.logEventsCount(logs.length),
                style: GoogleFonts.firaCode(
                  fontSize: 10,
                  color: colorScheme.onSurface.withAlpha(
                    ((0.4).clamp(0.0, 1.0) * 255).round(),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: SegmentedButton<_LogView>(
              segments: const [
                ButtonSegment<_LogView>(
                  value: _LogView.app,
                  label: Text('App Logs'),
                  icon: Icon(Icons.phone_android_outlined),
                ),
                ButtonSegment<_LogView>(
                  value: _LogView.runtime,
                  label: Text('ZeroClaw Logs'),
                  icon: Icon(Icons.memory_outlined),
                ),
              ],
              selected: {_activeView},
              onSelectionChanged: (selection) {
                final nextView = selection.first;
                setState(() => _activeView = nextView);
                if (nextView == _LogView.runtime) {
                  _refreshRuntimeLogs(showLoading: true);
                }
              },
            ),
          ),
          Expanded(
            child: useFocusMode
                ? _buildTvLogContainer(context, logContent, colorScheme)
                : _buildDesktopLogContainer(context, logContent, colorScheme),
          ),
        ],
      ),
    );
  }

  Future<void> _exportLogs() async {
    try {
      final service = context.read<ServiceManager>();
      final l10n = AppLocalizations.of(context)!;
      final logs = _activeView == _LogView.app ? service.logs : _runtimeLogs;
      if (logs.isEmpty) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l10n.noLogsToExport)));
        return;
      }

      final content = logs.join('\n');
      String downloadsDir;
      if (Platform.isWindows) {
        final userProfile = Platform.environment['USERPROFILE'] ?? '';
        downloadsDir = userProfile.isNotEmpty ? '$userProfile\\Downloads' : '.';
      } else {
        final home = Platform.environment['HOME'] ?? '';
        downloadsDir = home.isNotEmpty ? '$home/Downloads' : '.';
      }

      final ts = DateTime.now().toIso8601String().replaceAll(':', '-');
      final filename = _activeView == _LogView.app
          ? 'app_logs_$ts.txt'
          : 'zeroclaw_runtime_logs_$ts.txt';

      // Platform-specific save
      String savedPath = '';
      if (Platform.isAndroid) {
        // Use platform MethodChannel to write via MediaStore
        try {
          final bytes = Uint8List.fromList(content.codeUnits);
          final channel = MethodChannel('com.sipeed.zeroclaw/zeroclaw');
          final res = await channel.invokeMethod<String>('saveToDownloads', {
            'filename': filename,
            'bytes': bytes,
          });
          if (res != null) savedPath = res;
        } catch (_) {}
      }

      var isContentUri = savedPath.startsWith('content://');
      File? file;
      if (savedPath.isEmpty) {
        // Fallback: save to user Downloads (desktop / iOS / fallback on Android)
        final filePath = '$downloadsDir${Platform.pathSeparator}$filename';
        file = File(filePath);
        if (!await file.parent.exists()) {
          try {
            await file.parent.create(recursive: true);
          } catch (_) {}
        }
        await file.writeAsString(content);
        savedPath = file.path;
        isContentUri = false;
      } else if (!isContentUri) {
        // Native returned a real filesystem path
        file = File(savedPath);
      }

      // Notify user with a human-friendly message
      if (mounted) {
        final friendlyLocation = isContentUri
            ? l10n.logsSavedToMediaLibraryWithName(filename)
            : l10n.logsSavedToDownloads(file?.path ?? savedPath);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(friendlyLocation)));
      }

      try {
        // Handle case when we have a filesystem path first (applies to all platforms)
        if (file != null) {
          if (Platform.isWindows) {
            await Process.run('explorer', ['/select,${file.path}']);
          } else if (Platform.isMacOS) {
            await Process.run('open', ['-R', file.path]);
          } else if (Platform.isLinux) {
            await Process.run('xdg-open', [file.parent.path]);
          } else {
            // Mobile platforms: share the actual file
            final params = ShareParams(
              text: l10n.shareLogsText,
              files: [XFile(file.path)],
            );
            await SharePlus.instance.share(params);
          }
        } else if (isContentUri) {
          // We received a content:// URI (Android MediaStore) — share via XFile with URI
          try {
            // Try to copy content URI to app cache so share_plus can access it reliably
            final channel = MethodChannel('com.sipeed.zeroclaw/zeroclaw');
            String? cachePath;
            try {
              cachePath = await channel.invokeMethod<String>(
                'copyContentUriToCache',
                {'uri': savedPath, 'filename': filename},
              );
            } catch (_) {
              cachePath = null;
            }

            final shareFile = (cachePath != null && cachePath.isNotEmpty)
                ? XFile(cachePath)
                : XFile(savedPath);
            final params = ShareParams(
              text: l10n.shareLogsText,
              files: [shareFile],
            );
            await SharePlus.instance.share(params);
          } catch (e) {
            // Provide user-visible error if share failed and log for debugging
            if (mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(content: Text(l10n.shareFailed(e.toString()))),
              );
            }
            // also print to console to help with debugging on device
            // ignore: avoid_print
            print('Share failed for content URI $savedPath: $e');
          }
        } else {
          // No specific file or content URI — try to open containing folder when possible
          if (!Platform.isAndroid && !Platform.isIOS) {
            final folder = File(savedPath).parent.path;
            await Process.run('xdg-open', [folder]);
          }
        }
      } catch (e) {
        // ignore external invocation/share errors; user already informed of saved location
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Failed to export logs: $e')));
      }
    }
  }

  // TV 平台：使用 TVFocusable 包装，保持焦点样式一致
  Widget _buildTvLogContainer(
    BuildContext context,
    Widget logContent,
    ColorScheme colorScheme,
  ) {
    return TVFocusable(
      onTap: () {},
      borderRadius: BorderRadius.circular(20),
      autofocus: true,
      showFocusGlow: false,
      focusBackgroundColor: Colors.transparent,
      focusPadding: const EdgeInsets.symmetric(horizontal: 4),
      child: Container(
        margin: const EdgeInsets.all(6),
        decoration: BoxDecoration(
          color: colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: colorScheme.outlineVariant),
        ),
        padding: const EdgeInsets.all(16),
        child: _buildLogBody(logContent, colorScheme),
      ),
    );
  }

  // 非 TV 平台：普通容器
  Widget _buildDesktopLogContainer(
    BuildContext context,
    Widget logContent,
    ColorScheme colorScheme,
  ) {
    return Container(
      margin: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: colorScheme.outlineVariant),
      ),
      padding: const EdgeInsets.all(16),
      child: _buildLogContainerBody(logContent, colorScheme),
    );
  }

  Widget _buildLogContainerBody(Widget logContent, ColorScheme colorScheme) {
    if (_activeView == _LogView.runtime) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildRuntimeDiagnosticsPanel(colorScheme),
          const SizedBox(height: 12),
          Expanded(child: _buildLogBody(logContent, colorScheme)),
        ],
      );
    }

    return _buildLogBody(logContent, colorScheme);
  }

  Widget _buildLogBody(Widget logContent, ColorScheme colorScheme) {
    if (_activeView == _LogView.runtime &&
        _isRuntimeLogLoading &&
        _runtimeLogs.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_activeView == _LogView.runtime && _runtimeLogs.isEmpty) {
      final runtimeLogPath =
          (_runtimeDiagnostics['runtimeLogPath'] as String?) ??
          '/data/coclaw/zeroclaw.log';
      final lastReadError =
          (_runtimeDiagnostics['lastRuntimeLogReadError'] as String?) ?? '';
      final stopDetails =
          (_runtimeDiagnostics['lastStopDiagnostics'] as String?) ?? '';
      return Center(
        child: Text(
          'No ZeroClaw runtime logs found yet.',
          style: TextStyle(color: colorScheme.onSurfaceVariant),
          textAlign: TextAlign.center,
        ),
      );
    }

    if (_activeView == _LogView.app &&
        (context.read<ServiceManager>().logs.isEmpty)) {
      return Center(
        child: Text(
          'No app logs yet.',
          style: TextStyle(color: colorScheme.onSurfaceVariant),
          textAlign: TextAlign.center,
        ),
      );
    }

    return logContent;
  }

  Widget _buildRuntimeDiagnosticsPanel(ColorScheme colorScheme) {
    final items = <String>[
      'appUid=${_runtimeDiagnostics['appUid'] ?? '?'}',
      'appPid=${_runtimeDiagnostics['appPid'] ?? '?'}',
      'serviceTrackedPid=${_runtimeDiagnostics['serviceTrackedPid'] ?? '?'}',
      'detectedRuntimePid=${_runtimeDiagnostics['detectedRuntimePid'] ?? '?'}',
      'detectedSource=${_runtimeDiagnostics['detectedRuntimeSource'] ?? ''}',
      'homeExists=${_runtimeDiagnostics['homeExists'] ?? '?'}',
      'homeReadable=${_runtimeDiagnostics['homeReadable'] ?? '?'}',
      'homeWritable=${_runtimeDiagnostics['homeWritable'] ?? '?'}',
      'runtimeLogExists=${_runtimeDiagnostics['runtimeLogExists'] ?? '?'}',
      'runtimeLogReadable=${_runtimeDiagnostics['runtimeLogReadable'] ?? '?'}',
      'runtimeLogWritable=${_runtimeDiagnostics['runtimeLogWritable'] ?? '?'}',
      'runtimeLogSizeBytes=${_runtimeDiagnostics['runtimeLogSizeBytes'] ?? 0}',
      'lastStopPid=${_runtimeDiagnostics['lastStopAttemptedPid'] ?? '?'}',
      'lastStopSucceeded=${_runtimeDiagnostics['lastStopSucceeded'] ?? '?'}',
    ];
    final notes = <String>[
      (_runtimeDiagnostics['homePath'] ?? '').toString(),
      (_runtimeDiagnostics['runtimeLogPath'] ?? '').toString(),
      (_runtimeDiagnostics['lastRuntimeLogReadError'] ?? '').toString(),
      (_runtimeDiagnostics['lastStopAttemptAt'] ?? '').toString(),
      (_runtimeDiagnostics['lastStopDiagnostics'] ?? '').toString(),
    ].where((line) => line.trim().isNotEmpty).toList(growable: false);

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHighest.withAlpha(140),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: colorScheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Runtime Diagnostics',
            style: GoogleFonts.inter(
              fontWeight: FontWeight.w700,
              fontSize: 13,
              color: colorScheme.onSurface,
            ),
          ),
          const SizedBox(height: 8),
          SelectableText(
            items.join('  |  '),
            style: TextStyle(
              fontFamily: GoogleFonts.firaCode().fontFamily,
              fontSize: 11,
              color: colorScheme.onSurfaceVariant,
              height: 1.45,
            ),
          ),
          if (notes.isNotEmpty) ...[
            const SizedBox(height: 8),
            SelectableText(
              notes.join('\n'),
              style: TextStyle(
                fontFamily: GoogleFonts.firaCode().fontFamily,
                fontSize: 11,
                color: colorScheme.onSurfaceVariant,
                height: 1.45,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

enum _LogView { app, runtime }
