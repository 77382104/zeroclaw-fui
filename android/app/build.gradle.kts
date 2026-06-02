import java.util.Base64

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

fun String.toQuotedBuildConfigValue(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

fun decodedDartDefines(project: Project): Map<String, String> {
    val encoded = project.findProperty("dart-defines") as String? ?: return emptyMap()
    return encoded
        .split(',')
        .mapNotNull { value ->
            runCatching {
                String(Base64.getDecoder().decode(value), Charsets.UTF_8)
            }.getOrNull()
        }
        .mapNotNull { entry ->
            val separatorIndex = entry.indexOf('=')
            if (separatorIndex <= 0) {
                null
            } else {
                entry.substring(0, separatorIndex) to entry.substring(separatorIndex + 1)
            }
        }
        .toMap()
}

val dartDefines = decodedDartDefines(project)
val analyticsProvider = dartDefines["ZEROCLAW_ANALYTICS_PROVIDER"] ?: "firebase"
val umengAppKey = dartDefines["ZEROCLAW_UMENG_APP_KEY"] ?: ""
val umengChannel = dartDefines["ZEROCLAW_UMENG_CHANNEL"] ?: "official"
val umengLinkScheme = if (umengAppKey.isNotBlank()) {
    "um.$umengAppKey"
} else {
    "um.placeholder"
}

// Firebase Configuration from dart-define
val firebaseAppId = dartDefines["ZEROCLAW_FIREBASE_APP_ID"] ?: ""
val firebaseApiKey = dartDefines["ZEROCLAW_FIREBASE_API_KEY"] ?: ""
val firebaseProjectId = dartDefines["ZEROCLAW_FIREBASE_PROJECT_ID"] ?: ""
val firebaseMessagingSenderId = dartDefines["ZEROCLAW_FIREBASE_MESSAGING_SENDER_ID"] ?: ""
val firebaseStorageBucket = dartDefines["ZEROCLAW_FIREBASE_STORAGE_BUCKET"] ?: ""

android {
    namespace = "com.sipeed.zeroclaw"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.sipeed.zeroclaw"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        buildConfigField("String", "ZEROCLAW_ANALYTICS_PROVIDER", analyticsProvider.toQuotedBuildConfigValue())
        buildConfigField("String", "ZEROCLAW_UMENG_APP_KEY", umengAppKey.toQuotedBuildConfigValue())
        buildConfigField("String", "ZEROCLAW_UMENG_CHANNEL", umengChannel.toQuotedBuildConfigValue())
        buildConfigField("String", "ZEROCLAW_UMENG_LINK_SCHEME", umengLinkScheme.toQuotedBuildConfigValue())
        // Pass values to AndroidManifest.xml via manifestPlaceholders
        manifestPlaceholders["ZEROCLAW_UMENG_APP_KEY"] = umengAppKey
        manifestPlaceholders["ZEROCLAW_UMENG_CHANNEL"] = umengChannel
        manifestPlaceholders["ZEROCLAW_UMENG_LINK_SCHEME"] = umengLinkScheme
    }

    signingConfigs {
        create("release") {
            // Signing configuration loaded from environment variables
            // For CI/CD: set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD as secrets
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: ""
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            val keyAlias = System.getenv("KEY_ALIAS") ?: ""
            val keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            
            if (keystorePath.isNotEmpty() && keystorePassword.isNotEmpty() && 
                keyAlias.isNotEmpty() && keyPassword.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (signingConfigs.named("release").get().storeFile?.exists() == true) {
                signingConfigs.getByName("release")
            } else {
                // Fallback to debug signing for local development
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/zeroclaw-assets"))
        }
    }

}

flutter {
    source = "../.."
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("javax.xml.stream:stax-api:1.0-2")
}

tasks.register<Copy>("generateBundledRuntimeAssets") {
    val sourceDir = layout.projectDirectory.dir("src/main/jniLibs")
    val outputDir = layout.buildDirectory.dir("generated/zeroclaw-assets")

    into(outputDir)
    includeEmptyDirs = true
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    from(sourceDir) {
        into("runtime")
    }

    from(sourceDir) {
        include("**/.zeroclaw/**")
        eachFile {
            val segments = relativePath.segments.toMutableList()
            val hiddenIndex = segments.indexOf(".zeroclaw")
            if (hiddenIndex >= 0) {
                segments[hiddenIndex] = "zeroclaw_seed"
                relativePath = RelativePath(true, *segments.toTypedArray())
            }
        }
        into("runtime-seed")
    }
}

// Generate Firebase resources from dart-define
tasks.register("generateFirebaseResources") {
    doLast {
        val resDir = file("src/main/res/values")
        resDir.mkdirs()
        
        val stringsXml = file("$resDir/strings.xml")
        
        // Build the content - always generate required fields even if empty
        // to prevent AAPT errors when AndroidManifest references them
        val content = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<resources>")
            appendLine("    <!-- Auto-generated from dart-define, do not edit manually -->")
            
            // Always generate google_app_id (required by AndroidManifest.xml)
            appendLine("    <string name=\"google_app_id\" translatable=\"false\">${firebaseAppId.xmlEscape()}</string>")
            
            if (firebaseApiKey.isNotEmpty()) {
                appendLine("    <string name=\"google_api_key\" translatable=\"false\">${firebaseApiKey.xmlEscape()}</string>")
            }
            if (firebaseProjectId.isNotEmpty()) {
                appendLine("    <string name=\"project_id\" translatable=\"false\">${firebaseProjectId.xmlEscape()}</string>")
                appendLine("    <string name=\"firebase_database_url\" translatable=\"false\">https://${firebaseProjectId.xmlEscape()}.firebaseio.com</string>")
            }
            if (firebaseMessagingSenderId.isNotEmpty()) {
                appendLine("    <string name=\"gcm_defaultSenderId\" translatable=\"false\">${firebaseMessagingSenderId.xmlEscape()}</string>")
            }
            if (firebaseStorageBucket.isNotEmpty()) {
                appendLine("    <string name=\"google_storage_bucket\" translatable=\"false\">${firebaseStorageBucket.xmlEscape()}</string>")
            } else if (firebaseProjectId.isNotEmpty()) {
                appendLine("    <string name=\"google_storage_bucket\" translatable=\"false\">${firebaseProjectId.xmlEscape()}.appspot.com</string>")
            }
            
            appendLine("</resources>")
        }
        
        stringsXml.writeText(content)
        println("Generated Firebase resources at: ${stringsXml.absolutePath}")
        println("Firebase Config: appId=${firebaseAppId.isNotEmpty()}, apiKey=${firebaseApiKey.isNotEmpty()}, projectId=${firebaseProjectId.isNotEmpty()}")
    }
}

// Helper function to escape XML
fun String.xmlEscape(): String {
    return this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

// Ensure resources are generated before any resource processing
afterEvaluate {
    // Hook into resource processing tasks which happen before AAPT linking
    tasks.findByName("mergeDebugResources")?.dependsOn("generateFirebaseResources")
    tasks.findByName("mergeReleaseResources")?.dependsOn("generateFirebaseResources")
    tasks.findByName("processDebugResources")?.dependsOn("generateFirebaseResources")
    tasks.findByName("processReleaseResources")?.dependsOn("generateFirebaseResources")
    // Also hook into pre-build tasks as fallback
    tasks.findByName("preBuild")?.dependsOn("generateFirebaseResources")
    tasks.findByName("preBuild")?.dependsOn("generateBundledRuntimeAssets")
    tasks.findByName("mergeDebugAssets")?.dependsOn("generateBundledRuntimeAssets")
    tasks.findByName("mergeReleaseAssets")?.dependsOn("generateBundledRuntimeAssets")
}

// Clean up sensitive resources after build
tasks.register("cleanupFirebaseResources") {
    doLast {
        val stringsXml = file("src/main/res/values/strings.xml")
        if (stringsXml.exists()) {
            stringsXml.delete()
            println("Cleaned up Firebase resources for security")
        }
    }
}

// Run cleanup after build completion - use afterEvaluate to ensure tasks exist
afterEvaluate {
    tasks.findByName("assembleDebug")?.finalizedBy("cleanupFirebaseResources")
    tasks.findByName("assembleRelease")?.finalizedBy("cleanupFirebaseResources")
    tasks.findByName("bundleRelease")?.finalizedBy("cleanupFirebaseResources")
}
