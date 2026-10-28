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

    /*
     * 影厅资源不压缩（父亲 2026-10-10 装机实测踩坑）：assets 里被压缩的文件
     * AssetManager.openFd() 会直接抛异常 —— 界面层第一版就用 openFd 量大小，
     * 于是"影厅几何缺失"，VR 里只剩黑背景。不压缩后 openFd 可用，
     * 拷贝时也不用现解压（8.5 MB 几何本来就不该再压）。
     */
    androidResources {
        noCompress += listOf("b0bcin", "b0benv")
    }

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
        /*
         * 2026-10-12：实验结束，包名改回正式版。
         *
         * 实验结论（见 AndroidManifest.xml 里的完整记录）：去掉 `pvr.app.type`
         * 之后，同一个应用在 PICO 资源库里就从「未知来源」变成「应用」——
         * 分类开关就是这一条，与包名无关（实验版用的是 .exp 包名，同样进「应用」）。
         */
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
        /* 父亲 2026-10-28：主版本号从 0.1 提到 1.0，末段仍是每次编译的 run 号 */
        versionName = "1.0.$buildNo"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        /*
         * 【2026-10-20 15:35 换内核：Chromium 132 的硬要求】
         * crwebview 官方说明：内核的数据包（.pak / .bin）一旦被 apk 压缩，运行时读不出来，
         * 内核直接起不来。这条不写，装上就是白屏。
         */
        androidResources {
            noCompress += listOf("bin", "pak")
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

/*
 * 火狐内核（GeckoView）依赖 kotlin-stdlib 2.3.20，而本项目的 Kotlin 编译器是 2.0.21 ——
 * 不锁回来的话 Gradle 会挑 2.3.20，编译期直接报
 * 「Module was compiled with an incompatible version of Kotlin（metadata 2.3.0 > 2.0.0）」（run 505 实测）。
 * 内核那边用到的标准库接口都是老 API，锁到项目版本即可。
 */
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
    }
}

/*
 * 见下方 dependencies 注释①：内核库自带 jspecify 注解类，排掉外部那一份，避免打包判重复。
 */
configurations.configureEach {
    exclude(group = "org.jspecify", module = "jspecify")
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

    /*
     * 【2026-10-20 16:18 父亲要求：火狐内核整套拆掉】
     *
     * 原因：火狐内核切不动腾讯的清晰度（同账号同页面，谷歌内核能切），换内核后它就没有用了；
     * 它还占着包体一大半（原包 288MB 里火狐原生库是大头），留着只会让安装包继续臃肿。
     * 网页内核改由下面 crwebview（Chromium 132）承担。
     */

    /*
     * 【2026-10-20 父亲定：换成 Chromium 132 内核】
     *
     * 为什么换：腾讯网页的清晰度切换在火狐内核里切不动（同账号同页面，.18 的谷歌内核能切，
     * 火狐切不动，当天 16 次编译逐条排除后只剩内核这个变量，详见
     * docs/handoffs/b0bemby-vr-tencent-quality-2026-10-20.md）。
     *
     * 来源：github.com/wuruxu/crwebview 的 2.0.0（基于 Chromium 132，2025-01）。
     * 它把 Chromium 的网页视图原样导出成 aar，**类名只在独立命名空间下，接口与系统完全一致**，
     * 所以调用侧只需换 import。库里只有 arm64-v8a 一套原生库，不用裁剪。
     * 另一份是内核资源包（几十 KB），缺它内核同样起不来。
     */
    implementation(files("libs/android_crwebview_webkit.WebView-v2.0.0.aar"))
    /*
     * 【2026-10-20 15:48 不引 android_webview_resources-debug.aar（run 788 实证）】
     *
     * 那个包只有菜单/对话框这类界面资源，而且引用了 AOSP 系统内部的私有资源
     * （dimen/context_menu_*、color/dialog_bg_color_baseline 等），普通应用拿不到，
     * aapt 直接链接失败。内核运行真正需要的资源（pak / bin / icudtl / 语言包）都在主 aar 里，
     * 已经随主 aar 一起打进包，所以这一个整包不要。
     */
    /*
     * 【2026-10-20 16:22 资源包必须引回来】
     *
     * 它提供内核启动时要读的资源索引表（org.chromium.android_webview.R$raw）。
     * 之前引它会失败，是因为里面的菜单布局引用了系统私有资源名；这些名字已在
     * app/src/main/res/values/aw_res_compat.xml 里补齐，所以现在能正常编过。
     */
    implementation(files("libs/android_webview_resources-debug.aar"))

    /*
     * 【2026-10-20 16:07 闪退根因：还缺三个配套包】
     *
     * 实机日志原文：Caused by: java.lang.ClassNotFoundException: org.chromium.ui.R$integer
     * 内核启动时要读这个资源 id 表，它不在主 aar 里，而在作者同时发布的三个小包里
     * （每个只有几十到一百多 KB）。缺一个就在打开网页那一刻闪退。
     */
    implementation(files("libs/content-debug.aar"))
    implementation(files("libs/ui-debug.aar"))
    implementation(files("libs/embedder_support_resources-debug.aar"))

    /*
     * 【2026-10-20 15:45 两处编译冲突（run 787 实证）】
     *
     * ① Duplicate class org.jspecify.annotations.NonNull：
     *    内核库把 jspecify 的注解类打在自己包里了，依赖树里又有 org.jspecify:jspecify:1.0.0，
     *    两边同名 → 打包时判重复。jspecify 只是编译期注解、运行时不用，直接排掉外部那份。
     *
     * ② 内核库的清单引用了 Theme.AppCompat.DayNight，而本项目从来没有 appcompat 依赖
     *    → aapt 链接资源时报 not found。补上 appcompat 即可（只用到它的主题，不引界面）。
     */
    implementation("androidx.appcompat:appcompat:1.6.1")

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
    // 0.4.1-dovi：MediaCodec 硬解挂杜比视界 RPU 侧数据（解决 P5 偏色），本地 AAR
    implementation(files("libs/libmpv-dovi.aar"))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
}
