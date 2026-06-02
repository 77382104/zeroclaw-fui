# ZeroClaw Android

ZeroClaw 是一款基于 Flutter 开发的 Android 应用，提供原生运行时服务支持。

> 本项目参考自 [sipeed/picoclaw_fui](https://github.com/sipeed/picoclaw_fui)

## 项目结构

```
android/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/sipeed/zeroclaw/
│   │   │   ├── MainActivity.kt          # 主 Activity，处理权限和 Intent
│   │   │   ├── ZeroClawApp.kt           # Application 入口，初始化通知渠道
│   │   │   ├── StartupTrace.kt          # 启动性能追踪
│   │   │   ├── RuntimeMethodChannel.kt  # Flutter 与原生通信通道
│   │   │   ├── receiver/
│   │   │   │   └── BootReceiver.kt      # 开机自启广播接收器
│   │   │   ├── service/
│   │   │   │   └── ZeroClawService.kt   # 核心前台服务，管理运行时进程
│   │   │   ├── runtime/
│   │   │   │   ├── RuntimeModels.kt     # 数据模型定义
│   │   │   │   └── RuntimeRegistry.kt   # 运行时注册表
│   │   │   └── util/
│   │   │       └── HealthChecker.kt     # 健康检查工具
│   │   ├── AndroidManifest.xml          # 应用配置和权限声明
│   │   └── res/                         # 资源文件
│   └── build.gradle.kts                 # 应用级构建配置
├── build.gradle.kts                     # 项目级构建配置
└── settings.gradle.kts                  # Gradle 设置
```

## 主要功能

### 核心服务
- **前台服务 (ZeroClawService)**: 管理 zeroclaw 运行时进程，支持自动重启和日志记录
- **开机自启**: 通过 BootReceiver 实现设备启动后自动运行
- **通知管理**: 持久化通知栏显示，支持快速停止服务

### 权限说明
- `INTERNET` / `ACCESS_NETWORK_STATE`: 网络访问
- `FOREGROUND_SERVICE`: 前台服务运行
- `RECEIVE_BOOT_COMPLETED`: 开机自启
- `MANAGE_EXTERNAL_STORAGE`: 文件管理（Android 11+）
- `POST_NOTIFICATIONS`: 通知发送（Android 13+）
- `READ_PHONE_STATE`: 友盟统计设备信息
- `WAKE_LOCK`: 防止 CPU 休眠

### 数据分析
支持两种分析方案（通过 `dart-define` 配置）：
- **Firebase Analytics**: Google 分析服务
- **友盟统计**: 国内分析服务

## 构建配置

### 环境变量
发布版本签名配置（CI/CD）：
```bash
export KEYSTORE_PATH=/path/to/keystore.jks
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=your_alias
export KEY_PASSWORD=your_key_password
```

### Dart Defines
构建时通过 `dart-define` 传递配置：
```bash
flutter build apk \
  --dart-define=ZEROCLAW_ANALYTICS_PROVIDER=firebase \
  --dart-define=ZEROCLAW_FIREBASE_APP_ID=1:xxx:android:xxx \
  --dart-define=ZEROCLAW_FIREBASE_API_KEY=AIzaSy... \
  --dart-define=ZEROCLAW_FIREBASE_PROJECT_ID=project-id \
  --dart-define=ZEROCLAW_FIREBASE_MESSAGING_SENDER_ID=123456 \
  --dart-define=ZEROCLAW_FIREBASE_STORAGE_BUCKET=project-id.appspot.com
```

或友盟配置：
```bash
flutter build apk \
  --dart-define=ZEROCLAW_UMENG_APP_KEY=your_umeng_appkey \
  --dart-define=ZEROCLAW_UMENG_CHANNEL=official
```

## 技术栈

- **语言**: Kotlin 2.2.20
- **最低 API**: Flutter 默认值
- **编译 SDK**: Android Gradle Plugin 8.11.1
- **JVM 目标**: Java 17

## 核心组件说明

### ZeroClawService
核心服务类，负责：
- 启动/停止 zeroclaw 运行时进程
- 管理运行时日志
- 提供运行时状态诊断
- 支持进程崩溃自动重启（最多 3 次）
- Wake Lock 保持 CPU 唤醒

### MainActivity
Flutter 主 Activity，负责：
- 存储权限请求（MANAGE_EXTERNAL_STORAGE）
- 处理友盟链接唤起
- 与 Flutter 引擎绑定 Method Channel

### ZeroClawApp
Application 类，负责：
- 创建通知渠道
- 全局初始化

## 运行时目录结构

```
/data/data/com.sipeed.zeroclaw/files/zeroclaw/
├── zeroclaw              # 可执行二进制文件
├── web/dist/             # Web Dashboard 资源
├── .zeroclaw/            # 工作区配置
│   └── config.toml       # 运行时配置
└── zeroclaw.log          # 运行时日志
```

## 开发指南

### 本地调试
1. 确保已安装 Flutter SDK
2. 在 `local.properties` 中配置 `flutter.sdk` 路径
3. 运行 `flutter run` 进行调试

### 日志查看
- Logcat 标签：`ZeroClawService`, `MainActivity`, `RuntimeMethodChannel`
- 运行时日志：应用内查看或通过 `zeroclaw.log` 文件

## 注意事项

1. **签名配置**: 发布版本需要正确配置签名，否则无法安装更新
2. **权限申请**: 首次运行会引导用户授予必要权限
3. **后台限制**: 部分厂商系统需要在设置中允许后台运行
4. **日志清理**: 构建完成后会自动清理生成的敏感资源配置

## 相关项目

- [picoclaw_fui](../..) - 主项目仓库
