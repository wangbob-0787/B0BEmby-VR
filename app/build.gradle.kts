import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

/*
 * Emby 连接参数注入。
 *
 * 为什么不写死在 Kotlin 源码里：仓库是公开的（GitHub B0BEmby-VR），
 * API Key 进源码就等于公开泄露。改为从 `local.properties` 读，
 * 该文件在 .gitignore 里；CI 编译时用 GitHub Secrets 覆盖。
 *
 * 缺省值：服务器地址是内网固定地址，写死无妨；Key 留空，
 * 缺 Key 时应用仍能启动，只是海报墙为空并提示需要配置。
 */
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }

}
fun propOrEnv(key: String, default: String = ""): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: default)

android {
    namespace = "com.xxxx.emby_vr"
    compileSdk = 36

    buildFeatures {
        compose = true
        buildConfig = true
        // OpenXR loader 是 AAR 里的原生库，需要 prefab 才能被 CMake 找到
        // （见 app/src/main/cpp/CMakeLists.txt 的 find_package(openxr_loader)）
        prefab = true
    }

    /*
     * VR 影院模式（2026-10-05 起）：接官方 OpenXR，需要原生代码。
     * NDK 版本显式写死，避免 CI 上跟着 AGP 默认值漂移。
     */
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.xxxx.emby_vr"
        // PICO 4 = Android 10 / API 29；PICO 官方上传要求 minSdk 29
        minSdk = 29
        targetSdk = 33          // PICO OS 5.x 兼容；Google Play 上架线是 33，低于它 lint 会拦构建

        /*
         * 版本号必须每版递增，否则装机可能被系统跳过。
         *
         * 2026-10-03 实机踩坑：原先固定 versionCode=1 / versionName="0.1.0"，
         * 连出十几个包版本号都不变，父亲在头显里装完后「和上一版没有任何变化」——
         * 因为同版本号的包覆盖安装时，系统可能直接跳过不装（PICO 的安装器尤其如此），
         * 看起来就是改动没生效，极易误判成「代码没起作用」。
         *
         * 取值来源（按优先级）：
         *   1. 环境变量 BUILD_NUMBER（CI 里传 github.run_number）
         *   2. local.properties 的 BUILD_NUMBER
         *   3. 缺省 1
         */
        val buildNo = propOrEnv("BUILD_NUMBER", "1").toIntOrNull() ?: 1
        versionCode = buildNo
        versionName = "0.1.$buildNo"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        // Emby 连接参数：从 local.properties 或环境变量注入，不进源码
        buildConfigField("String", "EMBY_SERVER", "\"${propOrEnv("EMBY_SERVER", "http://192.168.150.15:6908")}\"")
        buildConfigField("String", "EMBY_API_KEY", "\"${propOrEnv("EMBY_API_KEY")}\"")
        buildConfigField("String", "EMBY_USER_ID", "\"${propOrEnv("EMBY_USER_ID", "40a02f8503ce4de49d58331a282dcea1")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    signingConfigs {
        // 与 B0BEmby(TV 版) 同一套固定证书：装机可用 pm install -r 覆盖，CI 里无需密钥
        create("fixedDebug") {
            storeFile = rootProject.file("keystore/b0bemby.p12")
            storeType = "PKCS12"
            storePassword = "b0bemby"
            keyAlias = "b0bemby"
            keyPassword = "b0bemby"
        }
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = keystoreProperties["storeFile"]?.let { file(it) }
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePropertiesFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("fixedDebug")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        // 家庭自用，不上架 Google Play：关掉 release 构建的致命检查，
        // 否则 ExpiredTargetSdkVersion 之类「上架要求」会直接拦死构建。
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.openxr.loader)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)

    // 面板层：Compose 界面挂到虚拟显示器的窗口上，需要手动设 ViewTree 三个 owner
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)

    // 面板层承载电视版真实界面所需（版本与电视版对齐）
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.zxing.core)
    implementation(libs.nanohttpd)

    implementation(libs.media3.exoplayer)
    // HLS 模块（2026-10-05 父亲实测：电视直播、部分转码片源"起播失败"，
    // 日志 ClassNotFoundException: media3.exoplayer.hls.HlsMediaSource$Factory
    // —— Emby 给直播和需要转码的内容返回 HLS 播放列表，缺这个模块就放不出来）
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    /*
     * FFmpeg 音频软解扩展（父亲 2026-10-07 定）：
     * 头显系统不给第三方应用 AC3/EAC3/DTS 的解码器，只能靠它自己软解。
     * 有了它就能如实声明"这些音轨我能放"，服务端不必转码 —— 直连原文件，
     * 有声、不卡、音画本来就同步。包是 Jellyfin 编好发到 Maven Central 的。
     */
    implementation(files("libs/media3-ffmpeg-decoder-1.5.0.aar"))

    // 自带解码内核（libmpv，FFmpeg 驱动）—— 用于系统解码器吃不下杜比视界这类片源
    implementation(libs.libmpv)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
}
