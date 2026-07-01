# ZeroClaw FUI 项目逻辑文档

> 本文档梳理 `zeroclaw_fui` 仓库的整体逻辑，重点解释**它解决什么问题、各层如何协作、关键数据流是什么**，而不是逐行复述代码。  
> 目标读者：刚加入项目的开发者、Code Reviewer、要做二次开发或维护的工程师。

---

## 1. 项目定位

ZeroClaw FUI 是 **ZeroClaw**（一个本地运行的 Go 二进制运行时 / Agent gateway）的**跨端 Flutter 客户端壳子**：

- **Android**：完整可用。负责拉起一个常驻**前台服务**运行 Go 二进制（`zeroclaw daemon`），并提供基于 `MethodChannel` 的原生能力（存储权限、开机自启、通知、健康检查、配置读写、日志读取）。
- **桌面端（Windows / macOS / Linux）**：通过 `Process.start` 直接启动打包好的 `zeroclaw.exe` / `zeroclaw` 二进制，提供系统托盘、单实例运行、WebView 嵌入、Window Manager 等桌面能力。

核心价值：**把一个命令行 runtime 包装成"开箱即用、桌面级体验"的应用**，同时给 ZeroClaw 的运行状态、配置、Web Dashboard、日志、AI Chat 提供统一的 GUI。

参考来源：`pubspec.yaml`（依赖项）、`README.md`（项目自述）、`lib/main.dart`、`android/app/src/main/AndroidManifest.xml`。

---

## 2. 顶层架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                       Flutter UI（Dart / lib）                        │
│  ┌──────────┐  ┌──────────┐  ┌──────┐  ┌──────┐  ┌────────────────┐   │
│  │Dashboard │  │ WebView  │  │ Chat │  │ Logs │  │ Config / About │   │
│  └────┬─────┘  └────┬─────┘  └───┬──┘  └───┬──┘  └────────┬───────┘   │
│       └──────────────┴────────────┴─────────┴──────────────┘           │
│                                │                                      │
│                       ServiceManager  (ChangeNotifier)               │
│                                │                                      │
│                  CoreServiceAdapter (抽象)                            │
│                  ├─ AndroidCoreServiceAdapter                         │
│                  │      └─ MethodChannel "com.sipeed.zeroclaw/zeroclaw"│
│                  └─ DesktopCoreServiceAdapter                        │
│                         └─ Process.start / http client                │
└─────────────────────────────────┬────────────────────────────────────┘
                                  │
              ┌───────────────────┴───────────────────┐
              ▼                                       ▼
   ┌───────────────────────┐               ┌────────────────────────┐
   │  Android Native (Kotlin)               │  Desktop Runtime (Go)  │
   │  ├─ MainActivity    (FlutterActivity)  │  zeroclaw daemon       │
   │  ├─ ZeroClawService (ForegroundService)│  -port <PORT>          │
   │  ├─ RuntimeMethodChannel               │  /health  /chat /web   │
   │  ├─ ZeroClawAndroidHost  (Profile)     │                        │
   │  ├─ RuntimeRegistry (active profile)   │                        │
   │  ├─ HealthChecker (/health polling)    │                        │
   │  ├─ BootReceiver (开机自启)            │                        │
   │  └─ ZeroClawApp (NotificationChannel)  │                        │
   └───────────────────────┘               └────────────────────────┘
```

设计要点：

- **UI 与运行时完全解耦**：`ServiceManager` 是 UI 唯一的状态源；运行时差异通过 `CoreServiceAdapter` 屏蔽。
- **MethodChannel 是边界**：Android 上所有原生能力（启停服务、读配置、读日志、读 dashboard 信息、健康检查、设置开机自启、读写工作区路径、存储权限申请、保存到 Downloads 等）都通过 `com.sipeed.zeroclaw/zeroclaw` 通道。
- **桌面端没有 MethodChannel**：`DesktopCoreServiceAdapter` 自己 `Process.start` 拉起二进制并读 stdout / stderr，通过本地 `http://127.0.0.1:<port>/health` 探测健康。

参考来源：`lib/src/core/service_manager.dart`、`lib/src/native/*.dart`、`android/app/src/main/kotlin/com/sipeed/zeroclaw/RuntimeMethodChannel.kt`。

---

## 3. 启动流程（main.dart）

`lib/main.dart` 的 `main(List<String> args)` 是整个 App 的入口，按平台分叉：

1. **绑定与性能追踪**  
   - `WidgetsFlutterBinding.ensureInitialized()`  
   - `StartupTrace.mark(...)` 全程打点，定位启动瓶颈。

2. **Windows 单实例**（仅 Windows）  
   `WindowsSingleInstance.ensureSingleInstance(args, ...)`：第二次启动会被旧实例截获，调用 `windowManager.show()/focus()` 把主窗口拉到前台。

3. **窗口管理**（非 Android / iOS）  
   `window_manager` 初始化 `WindowOptions`（1024×768，最低 850×650，居中，`setPreventClose(true)`），关闭按钮 = 隐藏窗口而不是退出。

4. **后台服务初始化**（仅 Android）  
   `initializeBackgroundService()` 来自 `lib/src/core/background_service.dart`，配置 `flutter_background_service` 的通知渠道，但 **autoStart=false**——真正的运行时由 `ZeroClawService`（Kotlin 前台服务）管理，这个 Dart 端的后台服务只是辅助保活。

5. **构建 `ServiceManager` 并 `init()`**  
   - 读取 `SharedPreferences`（host / port / binaryPath / arguments / publicMode / theme / locale）。  
   - Android 上额外调用 `ZeroClawChannel.getWebPort()` / `getAutoStart()` / `getWorkspacePath()` / `refreshRuntimeSetupStatus()` / `_syncNativeServiceStatus()`，并启动一个 3 秒一次的 `_nativePollingTimer`。  
   - 安装 `SIGINT` / `SIGTERM` 信号监听器（Windows 除外）→ 触发 `stop()`，保证命令行下 Ctrl+C 也能干净停服。  
   - 读取 `PackageInfo` 缓存 App 版本。

6. **渲染 UI**  
   `runApp(ChangeNotifierProvider.value(value: service, child: const MainApp()))`——把 `ServiceManager` 注入整个 widget tree，所有页面通过 `Provider.of` 监听状态。

`MainApp` 是 `MaterialApp`，`MainShell` 是底部 / 侧栏导航壳子（`AdaptiveActionBar`），五个 Tab：Dashboard / Web / Chat / Logs / Settings。

参考来源：`lib/main.dart`、`lib/src/core/background_service.dart`、`lib/src/core/service_manager.dart`。

---

## 4. `ServiceManager`：UI 唯一状态源

文件：`lib/src/core/service_manager.dart`，类 `ServiceManager extends ChangeNotifier with WidgetsBindingObserver`。

它是整个 GUI 的"单一可信源"，对外暴露：

### 4.1 状态字段

- `ServiceStatus status` —— 枚举：`stopped` / `running` / `starting`。
- `logs: List<String>` —— 进程日志缓冲（最多 500 行，防爆）。
- `host / port / binaryPath / arguments / publicMode / workspacePath` —— 运行时配置。
- `nativePid / healthStatus / healthUptime` —— 来自原生的实时健康信息。
- `autoStart / isRuntimeInitialized / isRuntimeInitializing / runtimeBinaryDir / runtimeBinaryPath / runtimeInitializationError` —— Android 专属 runtime 部署状态。
- `currentThemeMode / currentLocale` —— 主题与语言（持久化到 `SharedPreferences`）。
- `webUrl` —— `http://<host>:<port>`，给 WebView 与 Dashboard 共享。

### 4.2 核心行为

- `init()`：见 §3。
- `start()` / `stop()`：调用 `_adapter.startService(port:_port)` / `stopService()`。Android 上 `start()` 会检查 `_isRuntimeInitialized`，否则提示 "runtime is not initialized"。Desktop 上 `start()` 成功后立即把 `_status` 设为 `running`；Android 上则依赖 2 秒后的 `_syncNativeServiceStatus()` 回填。
- `updateConfig(host, port, binaryPath?, arguments?, publicMode?)`：把 UI 改的配置写回 `SharedPreferences`，Android 上强制 `host=0.0.0.0, publicMode=true`（公网可达）；Windows / Android 不修改 `binaryPath`（runtime 由系统托管）。
- `validateBinary([path])`：验证二进制是否存在且大小 > 0，失败时通过 `_adapter.getLastErrorCode()` 暴露错误码。
- `refreshRuntimeSetupStatus()` / `initializeRuntime()`：Android 专属，桥到 Kotlin 的 `getRuntimeSetupStatus` / `initializeRuntime`，把"是否已部署 runtime"的状态写回 UI，用于 Dashboard 提示用户初始化。
- `_syncNativeServiceStatus()`（每 3 秒）：Android 专属——调 `getServiceStatus` + `checkHealth` 同步 `isRunning / pid / isInitialized / binaryDir / binaryPath`，并把状态变更 `notifyListeners()`。
- `getDeviceIpAddress()`：枚举 IPv4 接口，优先返回以太网卡，其次 Wi-Fi，最后任意可用 IP（剔除 `127.0.0.1`、`169.254.*`、loopback）。Dashboard 用它生成扫码 URL。
- 日志桥接：`_addLog()` 通过 `Timer` 做 100ms 节流批处理，避免高频写入触发 `notifyListeners` 风暴。

### 4.3 信号 & 生命周期

- 监听 `SIGINT` / `SIGTERM`（除 Windows），保证 CLI 启动时 Ctrl+C 可干净退出。
- `WidgetsBindingObserver` 已挂载但 `didChangeAppLifecycleState` 当前是 no-op（之前用于友盟 / Firebase 统计，代码注释显示统计已移除，仅保留观察者占位）。
- 残留的"Device Feedback / Firebase 上报"代码路径全部返回固定文案 `"Device feedback reporting has been removed from this build."`，是历史 Firebase / Umeng 接入的残骸，目前不可用但保留 API 形态。

参考来源：`lib/src/core/service_manager.dart`。

---

## 5. 原生适配层（`lib/src/native/`）

### 5.1 `CoreServiceAdapter`（抽象）

定义统一的运行时控制接口：`startService / stopService / getServiceStatus / checkHealth / setAutoStart / getAutoStart / getCoreVersion / validateBinary / getWorkspacePath / setWorkspacePath / setConfiguredPath / setLogHandler / getLastErrorCode`。

### 5.2 `CoreServiceAdapterFactory.create()`

按平台返回实现：

- `Platform.isAndroid` → `AndroidCoreServiceAdapter`
- 其他 → `DesktopCoreServiceAdapter(binaryName, port, configuredPath)`  
  Windows 下二进制名 `zeroclaw.exe`，其他平台 `zeroclaw`。

### 5.3 `AndroidCoreServiceAdapter`

所有方法直接转发到 MethodChannel `com.sipeed.zeroclaw/zeroclaw`，逻辑都委托给 Kotlin 层：

- `startService/stopService` → `startService` / `stopService`
- `setAutoStart/getAutoStart` → 走原生 `SharedPreferences`
- `validateBinary(path)`：Android 端固定返回 `true`（runtime 来自 APK，无需校验）
- `setWorkspacePath`：固定返回 `false`（GUI 不允许改 workspace）
- `setConfiguredPath`：空操作（Android 由系统托管路径）

错误码通过 `_lastErrorCode` 暴露，UI 可以做更精细的提示。

### 5.4 `DesktopCoreServiceAdapter`

桌面端的核心实现，关键路径：

- **二进制解析**（`_resolveExePath()` / `_resolveCoreExePath()`）：依次在以下位置查找 `zeroclaw[.exe]`：
  1. 用户在 Settings 里配置的 `configuredPath`。
  2. `cwd/app/bin/`（开发模式）。
  3. `_findRepoRoot()` 找到含 `pubspec.yaml` 的仓库根，再找 `<root>/app/bin/`。
  4. 可执行文件所在目录及其 `bin/` 子目录。
  5. `PATH` 中所有目录。
  优先匹配 `zeroclaw-launcher[.exe]`，并通过 `version.txt` 中的平台 token（`windows` / `macos` / `linux`）选择匹配的二进制。

- **端口预清理**（`_preCleanup(port)`）：  
  - Windows：`netstat -ano | findstr :<port>` + `taskkill /F /PID`。  
  - macOS：`lsof -ti tcp:<port> | xargs kill -9`（BSD xargs 不支持 `-r`，空输入会被忽略）。  
  - Linux：`lsof -ti tcp:<port> | xargs -r kill -9`。

- **`startService({port, args})`**：  
  1. `_preCleanup(port)`。  
  2. 解析二进制路径，非 Windows 下 `chmod +x`。  
  3. `Process.start(exe, ['-port', port, ...args])`，然后 `_pipe()` 异步接管 stdout/stderr，通过 `setLogHandler` 推给 UI。  
  4. 用 `Future.any([exitCode, 700ms delay])` 做"立刻挂掉则视为启动失败"的快速失败检测（避免 `Process.isDetached` 状态问题）。

- **`stopService()`**：`proc.kill(ProcessSignal.sigkill)` 并清空缓存。

- **`checkHealth()`**：HTTP `GET http://127.0.0.1:<port>/health`，2s 超时，失败时回退到"是否还持有 `_proc`"做软判定。

- **`getCoreVersion()`**：`Process.run(exe, ['version'])`，从输出中用正则 `_semanticVersionPattern` 抠出 `x.y.z`。

参考来源：`lib/src/native/desktop_core_service_adapter.dart`。

---

## 6. Android 原生层（Kotlin）

### 6.1 类与职责一览

| 文件 | 职责 |
|---|---|
| `ZeroClawApp.kt` | `FlutterApplication` 子类。`onCreate()` 创建 `NotificationChannel("zeroclaw_service", IMPORTANCE_LOW)`，所有前台通知走这个渠道。 |
| `MainActivity.kt` | `FlutterActivity`。`configureFlutterEngine` 里挂 `RuntimeMethodChannel`；`onResume` 在 Android 11+ 检测 `MANAGE_EXTERNAL_STORAGE` 没授予则拉起系统设置页。 |
| `RuntimeMethodChannel.kt` | 整个 MethodChannel 的 dispatcher，所有 Dart ↔ Kotlin 调用都路由到这里。 |
| `runtime/RuntimeRegistry.kt` | 单例，目前只注册一个 `RuntimeProfile(id="zeroclaw", configFormat="toml", defaultHealthEndpoint="http://127.0.0.1:42618/health", startupMode="daemon", requiresPairing=true, supportsEmbeddedDashboard=true)`。 |
| `runtime/RuntimeModels.kt` | 数据模型：`RuntimeProfile / RuntimeLaunchRequest / DashboardInfo / ConfigDescriptor`。 |
| `runtime/ZeroClawAndroidHost.kt` | 把 `RuntimeProfile` 适配到 `ZeroClawService`，暴露 start/stop/health/dashboard/config 等高层 API，所有方法都在状态里补一个 `profileId` 字段。 |
| `service/ZeroClawService.kt` | **核心 ForegroundService**，下详。 |
| `util/HealthChecker.kt` | 纯 `HttpURLConnection` 的 `/health` 探测工具，2s connect/read 超时。 |
| `receiver/BootReceiver.kt` | 监听 `BOOT_COMPLETED`，如果 `SharedPreferences` 里 `auto_start=true` 且 runtime 已初始化，就 `ZeroClawService.start(context)`。 |

### 6.2 `ZeroClawService` —— ForegroundService 核心

> 文件：`android/app/src/main/kotlin/com/sipeed/zeroclaw/service/ZeroClawService.kt`

#### 目录与文件布局（应用 `filesDir`）

```
<filesDir>/zeroclaw/
├── zeroclaw                              # 解压后的 Go 二进制（可执行）
├── web/dist/                             # 内嵌 Web Dashboard 资源
├── .zeroclaw/
│   ├── config.toml                       # 主配置（runtime + gateway）
│   ├── zeroclaw.log                      # runtime 日志
│   ├── .runtime-initialized              # 初始化标记文件
│   └── .migrated-from-legacy             # 老路径迁移标记
└── tmp/                                  # TMPDIR
```

二进制查找优先级（`resolvePackagedBinarySource`）：

1. 已安装到 `filesDir/zeroclaw/zeroclaw`（运行时初始化时拷贝出来）。
2. `assets/runtime/<abi>/zeroclaw` 或 fallback `arm64-v8a` / `armeabi-v7a`。
3. 直接从 APK 的 `lib/<abi>/zeroclaw` ZipEntry 抽出。

> 注意：文档里出现的 `FIXED_HOME_PATH = "/data/local/tmp/zeroclaw"` 只是 `getRuntimeDiagnostics()` 报告用的"检查点路径"，**实际部署位置**是 `context.filesDir/zeroclaw/`（`getHomePath()`）。

#### 关键状态（companion object 静态）

- `isRunning: Boolean` —— 是否在跑。
- `processId: Int` —— 当前追踪的 PID。
- `lastLog: String` —— 最近的进程输出（用于 IPC 快速回报）。
- `lastStopAttemptedPid / lastStopAttemptAt / lastStopSucceeded / lastStopDiagnostics` —— 停服诊断。
- `bundledSeedSyncAttempted` —— 单进程内只做一次 seed 同步。

#### 启动流程（`ACTION_START`）

```
onStartCommand(ACTION_START)
  ├─ startForeground(NOTIFICATION_ID, notification)
  ├─ acquireWakeLock(PARTIAL_WAKE_LOCK, 24h)
  ├─ parse RuntimeLaunchRequest from Intent
  └─ startRuntime(request)
        ├─ 若 probeExistingRuntime 命中 → 复用现有 runtime，更新通知
        └─ 否则启动新线程
              ├─ check getRuntimeSetupStatus.isInitialized
              ├─ resolveInstalledBinaryFile（filesDir/zeroclaw/zeroclaw）
              ├─ runRuntime(binary, request)
              │     ├─ 必要时写默认 config.toml
              │     ├─ ProcessBuilder([zeroclaw, "daemon"])
              │     │    .directory(workspace)
              │     │    .environment(HOME/ZEROCLAW_HOME/ZEROCLAW_CONFIG/ZEROCLAW_TOKEN/...)
              │     ├─ 单独 logThread 读 stdout，append 到 logBuffer + runtime log file
              │     ├─ proc.waitFor()
              │     ├─ 退出码 != 0 且未 stopped → 最多 maxRestartAttempts=3 次重试，每次间隔 5s
              │     └─ updateNotification(...)
              └─ 任何异常 → appendRuntimeLogFileLine + updateNotification
```

#### 停止流程（`ACTION_STOP`）

```
stopRuntime()
  ├─ 记录 lastStopAttemptedPid / lastStopAttemptAt / lastStopDiagnostics
  ├─ synchronized 块内：stopped=true
  │    ├─ process?.destroy() → 2s 等待 → 仍存活就 destroyForcibly
  │    └─ logThread.interrupt()
  ├─ serviceThread.join(2s)
  └─ 后台线程：killRuntimePid(detectedPid)
        ├─ pidof / ps / toybox 多种方式查 PID
        ├─ TERM → 等 10×300ms
        ├─ KILL → 等 5×200ms
        └─ 全部失败则 lastStopSucceeded=false 并把诊断写入 lastStopDiagnostics
```

#### `probeExistingRuntime()` —— 复用现有 runtime

Android 上经常遇到 runtime 已经被外部拉起（比如手动 `adb shell` 跑过一次）。`probeExistingRuntime` 优先用 `/health` HTTP 探测，失败时降级到 `pidof` / `ps -A` + `/proc/<pid>/cmdline` 校验（cmdline 必须以 `zeroclaw` 开头）。如果命中则 `result.success(true)` 让 `MethodChannel.startService` 直接返回成功，不再 fork 进程。

#### `initializeRuntime()`

被 `MethodChannel.initializeRuntime` 调用，做事：

1. 清空 `filesDir/zeroclaw/` 下除日志外的所有内容（删除子目录与文件）。
2. `copyBundledRuntimeFromApk(overwrite=true)` 或 `copyBundledRuntimeFromAssets(overwrite=true)`，把 APK 里的 `lib/<abi>/zeroclaw` + `web/dist/` 全部展开。
3. `copyBundledWorkspaceSeedFromAssets`：从 `assets/runtime-seed/<abi>/zeroclaw_seed/` 把工作区种子（包括 `.zeroclaw/`）拷出来。
4. 修二进制可执行位 + 写默认 `config.toml` + 写 `.runtime-initialized` 标记文件。
5. 返回 `getRuntimeSetupStatus()` 给 Flutter，里面有 `isHomeReady / isBinaryInstalled / isConfigReady / isWebDistReady / isInitialized` 等细粒度标记。

`initializeRuntimeFallback()` 是 assets / APK 都没有命中时的兜底，只保证二进制 + web/dist + config 可用。

#### 默认 `config.toml`

```toml
[runtime]
profile = "zeroclaw"
host = "0.0.0.0"
public_mode = true

[gateway]
port = 42618
```

#### `runCommandForDiagnostics / runPidCommand / scanRuntimePidFromPs / isRuntimeProcess`

一组 `pidof` / `toybox pidof` / `ps -A` / `toybox ps -A` / `/proc/<pid>/cmdline` 的健壮封装，跨厂商 / 不同 Android 版本的兼容性靠它们撑起来。

#### 通知

`createNotification(status)` 用 `NotificationCompat.Builder(channel=zeroclaw_service)`，带：

- Content Intent → `MainActivity` (SINGLE_TOP)。
- Action → `Stop`（PendingIntent.getService 触发 `ACTION_STOP`）。
- `setOngoing(true)`、`CATEGORY_SERVICE`、`FOREGROUND_SERVICE_IMMEDIATE`。

#### 进程级环境变量（`buildEnvironment()`）

```
HOME               = <filesDir>/zeroclaw
ZEROCLAW_HOME      = <filesDir>/zeroclaw
ZEROCLAW_WORKSPACE = .zeroclaw
ZEROCLAW_CONFIG    = <workspace>/config.toml
ZEROCLAW_TOKEN     = <UUID，首次生成后缓存到 SharedPreferences>
TMPDIR             = <cacheDir>/tmp
PATH               = /system/bin:/system/xbin
LANG               = en_US.UTF-8
SSL_CERT_DIR       = /system/etc/security/cacerts
```

### 6.3 `RuntimeMethodChannel` —— Dart ↔ Kotlin 协议

Channel 名：`com.sipeed.zeroclaw/zeroclaw`

主要方法（节选）：

| Method | 行为 |
|---|---|
| `startService({port, args})` | 先检查 `isInitialized`，否则返回 `RUNTIME_NOT_INITIALIZED`；调 `ZeroClawService.probeExistingRuntime` 复用已有；否则通过 host 启动前台服务。 |
| `stopService` | `host.stopService()` |
| `getRuntimeSetupStatus` | 直接返回 setup status |
| `initializeRuntime` | `host.initializeRuntime()`，失败时把 status 当 details 回传 |
| `getServiceStatus` / `checkHealth` | health 在子线程跑，结果用 `mainExecutor` 回主线程 |
| `getConfig` / `parseConfig` / `saveConfig` | `host.readConfigText` + 简易 `[runtime]` / `[gateway]` TOML 解析 |
| `getCoreVersion` | 子线程跑，回主线程返回 |
| `getWorkspacePath` / `getHomePath` / `getConfigPath` | host 的 getter |
| `getFullLog` / `getRuntimeLogFileContent` | 内存 lastLog + 文件 `zeroclaw.log` |
| `getRuntimeDiagnostics` | 返回 home/workspace/config/log 的存在性、可读性、pid 等 |
| `getDashboardInfo` | `host.getDashboardInfo()` 转 map（url / supportsEmbeddedWebView / requiresAuth / authMode / token / headers） |
| `getAutoStart` / `setAutoStart` | 读写 `SharedPreferences("zeroclaw_prefs", "auto_start")` |
| `getRuntimeToken` | 持久化 UUID |
| `getWebPort` | 从 config.toml 解析 `[gateway] port`（默认 42618） |
| `isStorageManagerGranted` / `requestStorageManager` | Android 11+ 的 `MANAGE_EXTERNAL_STORAGE` 检查 / 跳转系统设置 |
| `getSafeDeviceInfo` | 制造商 + 型号 + Android 版本 + 设备分类（Tablet/Mobile 按最小宽度 ≥600dp） |
| `saveToDownloads` / `copyContentUriToCache` | MediaStore Download 写文件 + 缓存目录兜底 |

参考来源：`android/app/src/main/kotlin/com/sipeed/zeroclaw/RuntimeMethodChannel.kt`。

---

## 7. Flutter UI 页面

`MainShell` 用 `IndexedStack` + `PageTransitionSwitcher`（`SharedAxisTransition.vertical`）切换 5 个页面，侧栏 / 底栏（`AdaptiveActionBar`）根据屏幕宽度自适应。

### 7.1 DashboardPage（`lib/src/ui/dashboard_page.dart`）

- 顶部状态指示器（运行中 / 已停止 / 启动中）。
- 服务控制：Start / Stop 主按钮，状态联动。
- 公共模式下显示局域网 IP + **二维码**（`qr_flutter`），方便手机扫码访问 Web Dashboard。
- 显示设备信息、本机 IP、App 版本、Core 版本。
- Android 上展示 **runtime 初始化卡片**：`initializeRuntime()` 按钮（带二次确认对话框，提示会清空当前 runtime 内容），未初始化时禁用 Start。

### 7.2 WebViewPage（`lib/src/ui/webview_page.dart`）

- 通过 `ZeroClawChannel.getDashboardInfo()` 拿 `url / supportsEmbeddedWebView / requiresAuth / authMode / token / headers`。
- `url` 如果是 `0.0.0.0`，自动替换为 `127.0.0.1` 给 WebView 用。
- 平台分支：  
  - Android / iOS：`webview_flutter`。  
  - Windows：`webview_windows`。  
  - macOS：`desktop_webview_window`。  
  - Linux：`webview_flutter`（通常配合 WebKitGTK）。
- 顶部导航条（`WebViewNavBar`）支持刷新 / 前进后退 / 返回 Dashboard。
- 若 `requiresAuth=true`（默认 true，因为 `requiresPairing=true`），把 `token` 加到请求 header 里。

### 7.3 ChatPage（`lib/src/ui/chat_page.dart`）

- 从 `config.toml` 解析 `[channels_config.webchat] port` 与 `listen_path`（默认 42617 / `/response`）。
- 固定 `session_id = "tes13423671876997913"`。
- 支持 **流式**（`stream=true`，逐 chunk 更新 UI）和 **非流式**两种模式。
- POST JSON `{session_id, messages:[{role,content}], stream}`，30s 超时。
- UI 提供消息列表 + 输入框 + 流式开关，仅在 `ServiceStatus.running` 时允许发送。

### 7.4 LogPage（`lib/src/ui/log_page.dart`）

- 两个视图：
  - **App 日志**：来自 `ServiceManager.logs`（实时累加，最多 500 行）。
  - **Runtime 日志**：通过 `ZeroClawChannel.getRuntimeLogFileContent()` 读取 `zeroclaw.log`，每 2s 刷新一次。
- 自动滚动（手动上滚后禁用，回到底部重新启用）。
- 键盘 ↑/↓ 滚动、TV 遥控器焦点支持（`FocusNode` / `LogicalKeyboardKey`）。
- 可分享 / 导出日志（`share_plus` + Android 的 `saveToDownloads`）。

### 7.5 ConfigPage（`lib/src/ui/config_page.dart`，最大文件 1884 行）

按 Tab / 段落组织：

- **General**：host / port / publicMode / 二进制路径 / 启动参数。
- **Runtime**：原始 `config.toml` 编辑器（含 dirty 跟踪、保存、未保存提示）。
- **Language / Theme**：`AppThemeMode`（carbon / slate / obsidian / ebony / nord / sakura）和 13 种语言。
- **Analytics**：残留的 Firebase 上报开关，已 disable 但保留 UI。
- **About**：App / Core 版本号、外链（GitHub、官网）。

Dirty 跟踪 + 未保存切换拦截逻辑很典型：`MainShell._onNavTap` 监听切换，若离开 Config 页且有改动，弹"保存 / 丢弃"对话框，由 `ConfigPage` 暴露的 `_saveFn` 完成保存。

---

## 8. 多端差异化要点

| 维度 | Android | 桌面（Windows / macOS / Linux） |
|---|---|---|
| 启动 runtime | Kotlin `ZeroClawService` ForegroundService + `ProcessBuilder` 拉 Go 二进制 | Dart `Process.start` 拉 Go 二进制 |
| 二进制来源 | APK `lib/<abi>/` 或 `assets/runtime/<abi>/`，首次启动解压到 `filesDir/zeroclaw/` | `app/bin/<platform>` 或 `PATH` 或 Settings 里手填的路径 |
| 通信 | `MethodChannel com.sipeed.zeroclaw/zeroclaw` | 直接 Dart ↔ Process / HTTP |
| 自启 | `BootReceiver`（BOOT_COMPLETED） + `auto_start` 偏好 | `setAutoStart` 桌面端写为 `true` 占位（未实现） |
| 通知 | `NotificationCompat` + `NotificationChannel(IMPORTANCE_LOW)` | 系统托盘 `tray_manager` |
| 单实例 | Android 默认就是 | Windows 用 `windows_single_instance` |
| 关闭按钮 | 默认退出 | `windowManager.setPreventClose(true)` → 隐藏窗口，托盘右键退出 |
| 窗口管理 | `MainActivity` 主题 | `window_manager` 1024×768 / min 850×650 |
| 存储权限 | `MANAGE_EXTERNAL_STORAGE`（Android 11+）+ MediaStore Downloads | 直接文件系统 |

---

## 9. 国际化与主题

- `flutter_intl.enabled = true`（见 `pubspec.yaml`），语言资源放在 `lib/l10n/app_*.arb` + `lib/src/generated/l10n/app_localizations*.dart`，覆盖：英、中（zh）、日、韩、法、德、西、俄、葡、阿、印地、印尼 共 13 种。
- 主题 6 套（`AppThemeMode`）：carbon / slate / obsidian / ebony / nord / sakura，全部基于 `flex_color_scheme` + Google Fonts，配色策略统一为 "dark primary + bright secondary"。
- 启动时按系统语言匹配，落回 `en`。
- 切换语言 / 主题写入 `SharedPreferences`，启动时恢复。

---

## 10. 桌面壳特有行为

- `tray_manager`：装入 5 项菜单（显示窗口 / 启动服务 / 停止服务 / 分隔 / 退出），Linux 不设 tooltip 而是把应用名放进菜单项。
- `window_manager` 关闭拦截：按 X 不会退出，而是 `windowManager.hide()`，要彻底退出走托盘菜单 "Exit" → `service.stop(); exit(0)`。
- `bitsdojo_window`：仅 Windows 下用于窗口边缘拖拽 / Aero Snap 风格的微调。
- `windows_single_instance`：第二次启动自动把旧窗口拉到前台。

---

## 11. 数据流总览（一张图说清一次"Start"）

### Android

```
DashboardPage ▶ FilledButton("Start")
   └─ ServiceManager.start()
        ├─ 检查 _isRuntimeInitialized，未初始化 → 写日志 + notifyListeners，return
        ├─ _status = starting; notifyListeners
        └─ AndroidCoreServiceAdapter.startService(port)
             └─ MethodChannel('startService', {port, args})
                  └─ RuntimeMethodChannel.startService
                       ├─ 若 probeExistingRuntime 命中 → result.success(true)
                       └─ host.startService(request)
                            └─ ZeroClawAndroidHost.startService
                                 └─ ZeroClawService.start(context, request)
                                      └─ startForegroundService(Intent ACTION_START)
                                           └─ onStartCommand
                                                ├─ startForeground(notification)
                                                ├─ acquireWakeLock(24h)
                                                └─ serviceThread { runRuntime → ProcessBuilder([zeroclaw,"daemon"]) }
   ↑ 同时：
ServiceManager._nativePollingTimer 每 3s 调 _syncNativeServiceStatus()
   → getServiceStatus + checkHealth → 更新 isRunning / pid / healthStatus
```

### 桌面

```
DashboardPage ▶ FilledButton("Start")
   └─ ServiceManager.start()
        ├─ DesktopCoreServiceAdapter.startService(port)
        │     ├─ _preCleanup(port)        // netstat / lsof 杀旧进程
        │     ├─ _resolveExePath()        // app/bin / repoRoot / exeDir / PATH
        │     ├─ chmod +x（非 Windows）
        │     ├─ Process.start(exe, ['-port', port, ...args])
        │     ├─ _pipe(stdout/stderr → _logHandler → ServiceManager._addLog)
        │     └─ 700ms 内的立即退出 → 视为失败
        ├─ ok=true → _status = running; notifyListeners
        └─ WebView / Dashboard 直接打 http://127.0.0.1:<port>/
```

---

## 12. 配置文件 / 资源 / 构建配置

### `pubspec.yaml` 关键点

- 应用 ID：`zeroclaw_flutter_ui`，版本 `0.1.3+3`。
- 多端构建：`flutter_launcher_icons` 用 `assets/app_icon.png` 同时给 Android / iOS / Windows / macOS 出图标。
- 多语言：`flutter_intl.enabled = true`。
- 资产：`assets/app_icon.png`、`assets/icon.ico`（Windows 托盘用）。
- 关键依赖：`provider`、`web_socket_channel`、`webview_flutter`、`desktop_webview_window`、`webview_windows`、`flutter_background_service`、`flutter_local_notifications`、`tray_manager`、`window_manager`、`windows_single_instance`、`bitsdojo_window`、`shared_preferences`、`path_provider`、`process_run`、`package_info_plus`、`qr_flutter`、`remixicon`、`url_launcher`、`share_plus`、`http`、`archive`、`flex_color_scheme`、`google_fonts`、`file_picker`。

### `android/app/build.gradle.kts` 关键点

- `applicationId = "com.sipeed.zeroclaw"`。
- `compileSdk = flutter.compileSdkVersion`、`minSdk = flutter.minSdkVersion`、`targetSdk = flutter.targetSdkVersion`，JVM 17 + 核心库 desugaring。
- **签名**：从环境变量 `KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD` 读取，未配置则 fallback 到 debug 签名（仅本地开发）。
- **构建配置字段**：`ZEROCLAW_ANALYTICS_PROVIDER / ZEROCLAW_UMENG_APP_KEY / ZEROCLAW_UMENG_CHANNEL / ZEROCLAW_UMENG_LINK_SCHEME`。
- **manifestPlaceholders**：`ZEROCLAW_UMENG_APP_KEY` 等注入 `AndroidManifest.xml`。
- **生成 Firebase 资源**：`generateFirebaseResources` 任务根据 `--dart-define` 生成 `src/main/res/values/strings.xml`（`google_app_id` / `google_api_key` / `project_id` / `gcm_defaultSenderId` / `google_storage_bucket` / `firebase_database_url`），并把 `mergeDebugResources` / `mergeReleaseResources` / `processDebugResources` / `processReleaseResources` / `preBuild` 全部 dependsOn 这个任务。
- **嵌入 runtime 资源**：`generateBundledRuntimeAssets` 从 `src/main/jniLibs` 拷到 `build/generated/zeroclaw-assets/runtime`，并把 `.zeroclaw/**` 重写成 `zeroclaw_seed`。
- **清理敏感资源**：`assembleDebug / assembleRelease / bundleRelease` 都 finalizedBy `cleanupFirebaseResources`，构建完成后删除 `strings.xml` 防止泄露 Firebase 配置。

### `AndroidManifest.xml` 关键点

- 权限：`INTERNET / ACCESS_NETWORK_STATE / READ_PHONE_STATE / FOREGROUND_SERVICE(+SPECIAL_USE+DATA_SYNC) / WAKE_LOCK / RECEIVE_BOOT_COMPLETED / POST_NOTIFICATIONS / WRITE_EXTERNAL_STORAGE(maxSdk=28) / READ_EXTERNAL_STORAGE(maxSdk=32) / MANAGE_EXTERNAL_STORAGE`。
- `application`：`extractNativeLibs=true`（让 `getNativeLibraryDir` 能取到 `zeroclaw`），`requestLegacyExternalStorage=true`，`networkSecurityConfig=@xml/network_security_config`。
- `MainActivity`：`launchMode=singleTop`、空的 `taskAffinity`、`adjustResize`；额外挂一个 `intent-filter` 匹配 `${ZEROCLAW_UMENG_LINK_SCHEME}` 接收友盟唤起链接。
- 服务：
  - `.service.ZeroClawService`（`foregroundServiceType=specialUse`，`exported=false`）。
  - `id.flutter.flutter_background_service.BackgroundService`（`foregroundServiceType=dataSync`，`exported=false`，`tools:replace="android:exported"`）。
- 接收器：`.receiver.BootReceiver`，监听 `BOOT_COMPLETED`。
- meta-data：`flutterEmbedding=2`、友盟 `UMENG_APPKEY` / `UMENG_CHANNEL`、Firebase `google_app_id` / `APPLICATION_ID`（实际值来自自动生成的 `strings.xml`）。

---

## 13. 已知 / 设计遗留

- **`ServiceManager` 里有大量 "Device feedback reporting has been removed" 的占位实现**（`isDeviceFeedbackAllowed / uploadDeviceFeedbackReport / ...`），是早期 Firebase / Umeng 上报的残骸，UI 层不再使用，保留 API 形态方便日后回归。
- **`AndroidCoreServiceAdapter.validateBinary` 永远返回 `true`**——Android 上 runtime 由系统托管，没有"二进制缺失"概念。
- **`AndroidCoreServiceAdapter.setWorkspacePath` 永远返回 `false`**——workspace 由原生通过 `ZEROCLAW_HOME` 控制。
- **`DesktopCoreServiceAdapter.setAutoStart / getAutoStart` 是占位**（返回 `true` / `false`），桌面端还没有真正的开机自启实现。
- **仪表盘运行时 ID / `FIXED_HOME_PATH` 与实际部署位置不一致**：文档与 `getRuntimeDiagnostics()` 都报告 `/data/local/tmp/zeroclaw`，但实际写的是 `context.filesDir/zeroclaw/`。这是历史遗留的硬编码，调试时要看清楚。

---

## 14. 修改指引

| 目标 | 应该改的地方 |
|---|---|
| 加一个新页面 / Tab | `lib/main.dart` 的 `_buildNavButton` + `IndexedStack.children`，新建 `lib/src/ui/<page>.dart` |
| 加一个新的原生能力 | Kotlin 端在 `RuntimeMethodChannel` 加 case + `ZeroClawAndroidHost` 暴露 API；Dart 端在 `ZeroClawChannel` 加静态方法 + `AndroidCoreServiceAdapter` 转调 |
| 桌面端找一个新放置位 | `DesktopCoreServiceAdapter._resolveExePath()` / `_resolveCoreExePath()` |
| 调整 runtime 启动参数 / 环境变量 | `ZeroClawService.buildEnvironment()` + `runRuntime()` |
| 调整 config.toml 默认值 | `ZeroClawService.ensureDefaultConfig()` + `defaultLaunchRequest()` |
| 调整启动顺序 / 增加初始化步骤 | `MainActivity.configureFlutterEngine` + `ZeroClawService.onStartCommand` |
| 加新主题 | `AppThemeMode` 枚举 + `AppTheme.getTheme()` switch |
| 加新语言 | `lib/l10n/app_<lang>.arb` + 重新生成 `lib/src/generated/l10n/` |
| 调整 runtime 部署策略 | `ZeroClawService.initializeRuntime` + `copyBundledRuntimeFromApk/Assets` |
| 调整开机自启策略 | `BootReceiver` + `RuntimeMethodChannel.setAutoStart/getAutoStart` |

---

## 附录：关键文件清单

```
android/
├── app/src/main/AndroidManifest.xml
├── app/src/main/kotlin/com/sipeed/zeroclaw/
│   ├── MainActivity.kt
│   ├── ZeroClawApp.kt
│   ├── StartupTrace.kt
│   ├── RuntimeMethodChannel.kt
│   ├── receiver/BootReceiver.kt
│   ├── service/ZeroClawService.kt
│   ├── runtime/
│   │   ├── RuntimeModels.kt
│   │   ├── RuntimeRegistry.kt
│   │   └── ZeroClawAndroidHost.kt
│   └── util/HealthChecker.kt
└── app/build.gradle.kts

lib/
├── main.dart
├── src/
│   ├── core/
│   │   ├── service_manager.dart           # UI 状态中心
│   │   ├── zeroclaw_channel.dart          # MethodChannel 客户端
│   │   ├── background_service.dart        # Flutter 后台服务（辅助）
│   │   ├── app_theme.dart                 # 6 套主题
│   │   ├── startup_trace.dart             # 启动追踪
│   │   ├── ui_constants.dart
│   │   └── device_feedback_models.dart    # 残留模型
│   ├── native/
│   │   ├── core_service_adapter.dart     # 抽象接口
│   │   ├── core_service_adapter_factory.dart
│   │   ├── android_core_service_adapter.dart
│   │   └── desktop_core_service_adapter.dart
│   ├── ui/
│   │   ├── dashboard_page.dart
│   │   ├── webview_page.dart
│   │   ├── chat_page.dart
│   │   ├── log_page.dart
│   │   ├── config_page.dart
│   │   ├── widgets/
│   │   │   ├── adaptive_action_bar.dart
│   │   │   └── tv_focusable.dart
│   │   └── webview/
│   │       ├── webview_android.dart
│   │       ├── webview_windows.dart
│   │       ├── webview_macos.dart
│   │       ├── webview_linux.dart
│   │       └── webview_nav_bar.dart
│   ├── generated/l10n/                    # 自动生成的多语言
│   └── l10n/                              # ARB 源文件
└── ...

assets/
├── app_icon.png
└── icon.ico
```