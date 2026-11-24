import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// ---- 崩溃监控 AppId（2026-11-05 需求 2）----------------------------------
// Bugly 必须在控制台注册应用后才能拿到 AppId；本仓库**不提交**这个值。
// 注入优先级：-PbuglyAppId=xxx → 环境变量 BUGLY_APP_ID → local.properties 的 bugly.appId → 空。
// 为空时 `CrashMonitor` 直接跳过初始化，构建与运行都不受影响（不会崩、不会上报）。
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
val buglyAppId: String = (project.findProperty("buglyAppId") as String?)
    ?: System.getenv("BUGLY_APP_ID")
    ?: localProperties.getProperty("bugly.appId")
    ?: ""

android {
    namespace = "com.morecheck"
    compileSdk = 36

    // ---- 诊断构建开关 ------------------------------------------------------
    // 默认 false：正式构建行为与以前完全一致（包名、版本号、R8 开关都不变）。
    // 排查真机崩溃时构建诊断变体：
    //   gradle assembleDiagRelease -PdiagBuild=true
    // 诊断包关掉 R8 并附 .diagnostic 后缀，用于与正式包做 A/B 对比。
    val diagBuild = (project.findProperty("diagBuild") as String?)?.toBoolean() ?: false

    defaultConfig {
        applicationId = "com.morecheck"
        minSdk = 29
        targetSdk = 36
        versionCode = 69
        versionName = "1.4.0"
        // 注入当前 git 短哈希到 BuildConfig（不在 git 仓库 / git 不可用时回退 "unknown"）。
        val gitSha = try {
            providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText.get().trim()
        } catch (e: Exception) {
            "unknown"
        }
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("boolean", "DIAG_BUILD", diagBuild.toString())
        // 崩溃监控的 AppId；空串 = 未配置，CrashMonitor 不初始化（见文件头的说明）。
        buildConfigField("String", "BUGLY_APP_ID", "\"$buglyAppId\"")
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
        }
    }

    buildTypes {
        release {
            // 正式包：包名与版本号保持原样，R8 全开。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }

        // 诊断变体：不继承 release，避免 isMinifyEnabled 的初始化顺序陷阱；
        // 这里显式声明所需要的每一项，并关闭 R8 / 资源收缩。
        create("diagRelease") {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".diagnostic"
            versionNameSuffix = "-diag"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 仅为诊断变体输出 BuildConfig.DIAG_BUILD；正式包该值为 false。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    // 「扫一扫」：CameraX 预览 / 分析 + ZXing 解码
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // 「实验室 → 数独」：WebViewAssetLoader（assets → 合法 https origin，
    // 保住页面里 Blob Worker 的题目生成策略 A）。见 ui/lab/SudokuScreen.kt 的 KDoc。
    implementation(libs.androidx.webkit)
    // 「设置 → 安全 → 开屏密码」（2026-10-25）：系统 BiometricPrompt（指纹/面部）。
    // 本轮唯一新增依赖，取稳定版 1.1.0（版本说明见 libs.versions.toml）。
    implementation(libs.androidx.biometric)
    // BiometricPrompt 的宿主要求 FragmentActivity，而 biometric 传递的 fragment
    // 停在 1.2.5（早于 ActivityResult 修复）；显式升到 1.8.x，Gradle 取最高版。
    implementation(libs.androidx.fragment.ktx)
    // ── 三方 SDK（2026-10-27）──────────────────────────────────────────────
    // OkHttp：把「热搜 / 腾讯热榜 / GitHub / 天气 / 云端」各自手写的
    // `HttpURLConnection` 收敛到一处（`data/net/AppHttp.kt`），连接池、超时、
    // 重定向、gzip 都由它处理；特殊请求头（如云服务要求的 Origin、GitHub 的 UA）
    // 用拦截器/Request 统一加，不再每个调用点各写一遍。
    implementation(libs.okhttp)

    // 崩溃监控（2026-11-05 需求 2）：只在同意隐私政策后初始化，见 util/CrashMonitor.kt
    implementation(libs.bugly.crashreport)

    // 「发现 → 音乐」在线播放（2026-11-05）：media3 ExoPlayer 核心，界面仍全 Compose
    implementation(libs.androidx.media3.exoplayer)

    // 音乐后台播放与通知栏/锁屏控制（2026-11-08）：MediaSession + MediaSessionService。
    // 与 exoplayer 同版本（media3 1.11.1），两个 artifact 必须同版本，否则运行时类冲突。
    implementation(libs.androidx.media3.session)
    // Coil 3（Compose 原生）：替掉聊天图片等处手写的 `BitmapFactory` + inSampleSize。
    // 网络抓取模块复用上面那个 OkHttpClient，不额外开线程池——本应用目前只显示
    // 本地 File，引它主要是为了将来"头像来自 URL"时不用再改依赖。
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.ui.tooling)

    // 纯 JVM 单元测试（v1.1.75 首次引入）。
    //
    // ## 为什么现在才加，以及为什么只加 junit
    //
    // 本项目此前的验证方式是「模拟器实测」+ HANDOVER 6.5 记的「换个语言重算一遍」
    // （课程表那轮用 Python 独立实现 RRULE 展开，与 Kotlin 对拍出 220 实例 / 84 天）。
    // 那个方法很有效，但它**是人工的**：解析器改一次就得重新做一遍，
    // 没有回归保护——220 / 84 这两个数字目前只存在于文档里，代码不会替你检查。
    //
    // 因此这里只为**纯 JVM 逻辑**引入最小测试栈（junit 4，不引 Robolectric / 不引
    // androidx.test）：`TimetableParser`、`StreakCalculator` 这类 String/日期进、
    // 数据出、零 Android 依赖的代码可以直接在 `src/test` 里跑，
    // 不需要模拟器、不增加 APK 体积（`testImplementation` 不进产物）。
    //
    // ⚠️ 有意**不**引 `androidTestImplementation`：那需要连设备/模拟器，
    // 与"提交前快速回归"的目的相反。UI 行为仍只能靠模拟器实测。
    testImplementation(libs.junit)

    // 真版 org.json（2026-11-08）：JVM 单测里 `org.json` 只有 android.jar 的空壳，
    // 调 `optJSONObject` 会抛 "Method ... not mocked"。加上官方实现后，
    // 搜索结果的**解析**（封面 https 化、缺字段丢弃）才能被单测覆盖，
    // 而不是只能靠模拟器手点。只在 testImplementation，不进 APK。
    testImplementation("org.json:json:20240303")
}

// release mapping 留存到交付目录：错误日志里的堆栈是混淆符号，
// 支持侧没有 mapping.txt 无法 retrace（见交付说明 §6）。
tasks.register<Copy>("copyMappingToDist") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/mapping/release/mapping.txt"))
    into(rootProject.layout.projectDirectory.dir("../dist"))
    rename { "MoreCheck-${android.defaultConfig.versionName!!.replace(' ', '-')}-mapping.txt" }
}
