import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:provider/provider.dart';
import 'package:zeroclaw_flutter_ui/src/core/service_manager.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zeroclaw_flutter_ui/src/generated/l10n/app_localizations.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:remixicon/remixicon.dart';
import 'package:zeroclaw_flutter_ui/src/core/startup_trace.dart';
import 'package:zeroclaw_flutter_ui/src/ui/widgets/tv_focusable.dart';

class DashboardPage extends StatefulWidget {
  const DashboardPage({super.key});

  @override
  State<DashboardPage> createState() => _DashboardPageState();
}

class _DashboardPageState extends State<DashboardPage> {
  String? _deviceIp;

  @override
  void initState() {
    super.initState();
    StartupTrace.markOnce('DashboardPage.initState');
    _loadDeviceIp();
  }

  Future<void> _loadDeviceIp() async {
    StartupTrace.mark('DashboardPage._loadDeviceIp start');
    final service = context.read<ServiceManager>();
    final ip = await service.getDeviceIpAddress();
    if (mounted) {
      setState(() => _deviceIp = ip);
    }
    StartupTrace.mark(
      'DashboardPage._loadDeviceIp end',
      details: 'ip=${ip ?? "null"}',
    );
  }

  Future<void> _confirmAndInitializeRuntime(ServiceManager service) async {
    final shouldInitialize = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('Confirm initialization'),
          content: const Text(
            'This will delete the current runtime contents except logs, and restore the workspace from the APK bundled files. Do you want to continue?',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('Initialize'),
            ),
          ],
        );
      },
    );

    if (shouldInitialize == true && mounted) {
      await service.initializeRuntime();
    }
  }

  @override
  void didUpdateWidget(covariant DashboardPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 当公共模式从关闭变为开启时，重新获取IP
    final service = context.read<ServiceManager>();
    if (service.publicMode && _deviceIp == null) {
      _loadDeviceIp();
    }
  }

  @override
  Widget build(BuildContext context) {
    StartupTrace.markOnce('DashboardPage.build');
    final service = context.watch<ServiceManager>();
    final l10n = AppLocalizations.of(context)!;
    final colorScheme = Theme.of(context).colorScheme;
    final canLaunch =
        service.status == ServiceStatus.running ||
        (service.isRuntimeInitialized && !service.isRuntimeInitializing);

    // 二维码数据：公共模式开启时使用设备IP，否则使用内部地址(webUrl)
    final qrData = (service.publicMode && _deviceIp != null)
        ? 'http://$_deviceIp:${service.webUrl.split(':').last}'
        : service.webUrl;

    return Scaffold(
      backgroundColor:
          Colors.transparent, // Let themed scaffoldBackground show through
      body: CustomScrollView(
        physics: const BouncingScrollPhysics(),
        slivers: [
          SliverAppBar(
            floating: true,
            backgroundColor: Colors.transparent,
            elevation: 0,
            centerTitle: false,
            title: Text(
              l10n.run.toUpperCase(),
              style: GoogleFonts.inter(
                fontWeight: FontWeight.w800,
                fontSize: 24,
                letterSpacing: -0.5,
              ),
            ),
            actions: [
              _buildStatusIndicator(context, service.status),
              const SizedBox(width: 24),
            ],
          ),
          SliverPadding(
            padding: const EdgeInsets.symmetric(
              horizontal: 32.0,
              vertical: 24.0,
            ),
            sliver: SliverList(
              delegate: SliverChildListDelegate([
                // High-Impact Control Center
                Padding(
                  padding: const EdgeInsets.only(bottom: 32.0),
                  child: Row(
                    children: [
                      if (defaultTargetPlatform == TargetPlatform.android) ...[
                        Expanded(
                          child: _buildPrimaryControl(
                            onTap: service.isRuntimeInitializing
                                ? null
                                : () => _confirmAndInitializeRuntime(service),
                            borderColor: colorScheme.tertiary,
                            backgroundColor: colorScheme.tertiary.withAlpha(
                              ((0.08).clamp(0.0, 1.0) * 255).round(),
                            ),
                            icon: service.isRuntimeInitialized
                                ? Icons.inventory_2_outlined
                                : Icons.settings_suggest_outlined,
                            iconColor: colorScheme.tertiary,
                            label: service.isRuntimeInitializing
                                ? 'Initializing'
                                : service.isRuntimeInitialized
                                ? 'Reset Runtime'
                                : 'Initialize',
                            labelColor: colorScheme.tertiary,
                          ),
                        ),
                        const SizedBox(width: 20),
                      ],
                      Expanded(
                        child: _buildPrimaryControl(
                          onTap: service.status == ServiceStatus.running
                              ? service.stop
                              : canLaunch
                              ? service.start
                              : null,
                          borderColor: service.status == ServiceStatus.running
                              ? colorScheme.error
                              : colorScheme.secondary,
                          backgroundColor:
                              service.status == ServiceStatus.running
                              ? colorScheme.error.withAlpha(
                                  ((0.06).clamp(0.0, 1.0) * 255).round(),
                                )
                              : !canLaunch
                              ? colorScheme.onSurface.withAlpha(
                                  ((0.05).clamp(0.0, 1.0) * 255).round(),
                                )
                              : colorScheme.secondary.withAlpha(
                                  ((0.08).clamp(0.0, 1.0) * 255).round(),
                                ),
                          icon: service.status == ServiceStatus.running
                              ? Remix.stop_circle_fill
                              : Remix.play_circle_fill,
                          iconColor: service.status == ServiceStatus.running
                              ? colorScheme.error
                              : !canLaunch
                              ? colorScheme.onSurface.withAlpha(
                                  ((0.35).clamp(0.0, 1.0) * 255).round(),
                                )
                              : colorScheme.secondary,
                          label: service.status == ServiceStatus.running
                              ? l10n.stopService
                              : l10n.launchService,
                          labelColor: service.status == ServiceStatus.running
                              ? colorScheme.error
                              : !canLaunch
                              ? colorScheme.onSurface.withAlpha(
                                  ((0.4).clamp(0.0, 1.0) * 255).round(),
                                )
                              : colorScheme.secondary,
                          iconSize: 42,
                          fontSize: 22,
                          fontWeight: FontWeight.w900,
                          letterSpacing: 1.5,
                          horizontalPadding: 32,
                          verticalPadding: 24,
                        ),
                      ),
                    ],
                  ),
                ),
                // Glassmorphism Status Card
                LayoutBuilder(
                  builder: (context, constraints) {
                    // 窄屏时垂直布局，宽屏时水平布局
                    final isNarrow = constraints.maxWidth < 600;

                    Widget infoSection = _buildInfoSection(
                      context,
                      service,
                      colorScheme,
                      l10n,
                    );

                    Widget qrSection = _buildQrSection(
                      context,
                      qrData,
                      colorScheme,
                    );

                    return Container(
                      decoration: BoxDecoration(
                        color: colorScheme.surface.withAlpha(
                          ((0.4).clamp(0.0, 1.0) * 255).round(),
                        ),
                        borderRadius: BorderRadius.circular(20),
                        border: Border.all(
                          color: colorScheme.onSurface.withAlpha(
                            ((0.08).clamp(0.0, 1.0) * 255).round(),
                          ),
                        ),
                      ),
                      clipBehavior: Clip.antiAlias,
                      child: isNarrow
                          ? Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                infoSection,
                                Container(
                                  width: double.infinity,
                                  height: 1,
                                  color: colorScheme.onSurface.withAlpha(
                                    ((0.05).clamp(0.0, 1.0) * 255).round(),
                                  ),
                                ),
                                Center(child: qrSection),
                              ],
                            )
                          : Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Expanded(flex: 3, child: infoSection),
                                Container(
                                  width: 1,
                                  height: 240,
                                  color: colorScheme.onSurface.withAlpha(
                                    ((0.05).clamp(0.0, 1.0) * 255).round(),
                                  ),
                                ),
                                qrSection,
                              ],
                            ),
                    );
                  },
                ),
                const SizedBox(height: 32),
                // Hint at bottom center
                if (defaultTargetPlatform == TargetPlatform.android) ...[
                  Padding(
                    padding: const EdgeInsets.only(top: 20, bottom: 28),
                    child: Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(20),
                      decoration: BoxDecoration(
                        color: colorScheme.surface.withAlpha(
                          ((0.45).clamp(0.0, 1.0) * 255).round(),
                        ),
                        borderRadius: BorderRadius.circular(18),
                        border: Border.all(
                          color: colorScheme.onSurface.withAlpha(
                            ((0.08).clamp(0.0, 1.0) * 255).round(),
                          ),
                        ),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            service.isRuntimeInitialized
                                ? 'Runtime is initialized'
                                : 'Runtime initialization is required before launch',
                            style: GoogleFonts.inter(
                              fontSize: 15,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                          const SizedBox(height: 10),
                          Text(
                            service.runtimeBinaryDir.isEmpty
                                ? 'Initializing runtime path...'
                                : service.runtimeBinaryDir,
                            style: GoogleFonts.firaCode(
                              fontSize: 13,
                              color: colorScheme.secondary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          if ((service.runtimeInitializationError ?? '')
                              .isNotEmpty) ...[
                            const SizedBox(height: 10),
                            Text(
                              service.runtimeInitializationError!,
                              style: TextStyle(
                                color: colorScheme.error,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                ],
                Center(
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        padding: const EdgeInsets.all(6),
                        decoration: BoxDecoration(
                          color: colorScheme.secondary.withAlpha(0),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Icon(
                          Icons.lightbulb_outline,
                          size: 20,
                          color: colorScheme.secondary,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Flexible(
                        child: Text(
                          service.publicMode
                              ? l10n.publicModeHint
                              : l10n.localModeHint,
                          style: TextStyle(
                            fontSize: 14,
                            color: colorScheme.onSurface.withAlpha(
                              ((0.7).clamp(0.0, 1.0) * 255).round(),
                            ),
                            height: 1.5,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 100),
              ]),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoSection(
    BuildContext context,
    ServiceManager service,
    ColorScheme colorScheme,
    AppLocalizations l10n,
  ) {
    return Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: colorScheme.secondary.withAlpha(
                    ((0.1).clamp(0.0, 1.0) * 255).round(),
                  ),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(
                  Remix.link_m,
                  color: colorScheme.secondary,
                  size: 18,
                ),
              ),
              const SizedBox(width: 12),
              Flexible(
                child: Text(
                  l10n.endpoint,
                  style: GoogleFonts.inter(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.5,
                    color: colorScheme.onSurface.withAlpha(
                      ((0.5).clamp(0.0, 1.0) * 255).round(),
                    ),
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          // 公共模式状态指示器
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                service.publicMode ? Icons.public : Icons.lock_outline,
                size: 16,
                color: colorScheme.secondary,
              ),
              const SizedBox(width: 6),
              Flexible(
                child: Text(
                  service.publicMode ? l10n.publicModeEnabled : l10n.localMode,
                  style: TextStyle(
                    fontSize: 13,
                    color: colorScheme.secondary,
                    fontWeight: FontWeight.bold,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          // Endpoint 显示地址：公共模式开启时使用设备IP，否则使用内部地址
          Builder(
            builder: (context) {
              // 公共模式开启但无法获取IP时显示警告
              if (service.publicMode && _deviceIp == null) {
                return TVFocusable(
                  onTap: null,
                  borderRadius: BorderRadius.circular(8),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          service.webUrl,
                          style: GoogleFonts.firaCode(
                            fontSize: 20,
                            color: colorScheme.secondary,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Row(
                          children: [
                            Icon(
                              Icons.warning_amber_rounded,
                              size: 14,
                              color: colorScheme.error,
                            ),
                            const SizedBox(width: 4),
                            Flexible(
                              child: Text(
                                l10n.unableToGetDeviceIp,
                                style: TextStyle(
                                  fontSize: 12,
                                  color: colorScheme.error,
                                ),
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                );
              }
              final displayUrl = (service.publicMode && _deviceIp != null)
                  ? 'http://$_deviceIp:${service.webUrl.split(':').last}'
                  : service.webUrl;
              return TVFocusable(
                onTap: () => launchUrl(Uri.parse(displayUrl)),
                borderRadius: BorderRadius.circular(8),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Text(
                    displayUrl,
                    style: GoogleFonts.firaCode(
                      fontSize: 20,
                      color: colorScheme.secondary,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              );
            },
          ),
          const SizedBox(height: 16),
          Text(
            l10n.webAdmin,
            style: TextStyle(
              color: colorScheme.onSurface.withAlpha(
                ((0.4).clamp(0.0, 1.0) * 255).round(),
              ),
              fontSize: 13,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPrimaryControl({
    required VoidCallback? onTap,
    required Color borderColor,
    required Color backgroundColor,
    required IconData icon,
    required Color iconColor,
    required String label,
    required Color labelColor,
    double iconSize = 42,
    double fontSize = 22,
    FontWeight fontWeight = FontWeight.w900,
    double letterSpacing = 1.5,
    double horizontalPadding = 32,
    double verticalPadding = 24,
  }) {
    return TVFocusable(
      onTap: onTap,
      borderRadius: BorderRadius.circular(24),
      focusBorderColor: borderColor,
      child: Container(
        padding: EdgeInsets.symmetric(
          vertical: verticalPadding,
          horizontal: horizontalPadding,
        ),
        decoration: BoxDecoration(
          color: backgroundColor,
          borderRadius: BorderRadius.circular(24),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: iconSize, color: iconColor),
            const SizedBox(width: 20),
            Flexible(
              child: Text(
                label,
                style: GoogleFonts.inter(
                  fontSize: fontSize,
                  fontWeight: fontWeight,
                  letterSpacing: letterSpacing,
                  color: labelColor,
                ),
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildQrSection(
    BuildContext context,
    String qrData,
    ColorScheme colorScheme,
  ) {
    return Container(
      width: 200,
      height: 240,
      decoration: BoxDecoration(
        color: colorScheme.onSurface.withAlpha(
          ((0.03).clamp(0.0, 1.0) * 255).round(),
        ),
      ),
      child: Center(
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(16),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withAlpha(
                  ((0.1).clamp(0.0, 1.0) * 255).round(),
                ),
                blurRadius: 20,
                spreadRadius: 5,
              ),
            ],
          ),
          child: QrImageView(
            data: qrData,
            version: QrVersions.auto,
            size: 140.0,
            gapless: true,
          ),
        ),
      ),
    );
  }

  Widget _buildStatusIndicator(BuildContext context, ServiceStatus status) {
    final colorScheme = Theme.of(context).colorScheme;
    final l10n = AppLocalizations.of(context)!;
    Color color;
    String label;
    switch (status) {
      case ServiceStatus.running:
        color = const Color(0xFF10B981); // Modern Emerald
        label = l10n.statusActive;
        break;
      case ServiceStatus.starting:
        color = const Color(0xFFF59E0B); // Modern Amber
        label = l10n.statusSyncing;
        break;
      case ServiceStatus.stopped:
        color = colorScheme.onSurface.withAlpha(
          ((0.3).clamp(0.0, 1.0) * 255).round(),
        );
        label = l10n.statusIdle;
        break;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withAlpha(((0.08).clamp(0.0, 1.0) * 255).round()),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
          color: color.withAlpha(((0.2).clamp(0.0, 1.0) * 255).round()),
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
              boxShadow: [
                if (status == ServiceStatus.running)
                  BoxShadow(
                    color: color.withAlpha(
                      ((0.6).clamp(0.0, 1.0) * 255).round(),
                    ),
                    blurRadius: 8,
                    spreadRadius: 1,
                  ),
              ],
            ),
          ),
          const SizedBox(width: 6),
          Text(
            label,
            style: GoogleFonts.inter(
              color: color,
              fontWeight: FontWeight.w800,
              fontSize: 10,
              letterSpacing: 1.2,
            ),
          ),
        ],
      ),
    );
  }
}
