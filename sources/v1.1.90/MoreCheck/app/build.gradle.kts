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
        versionCode = 39
        versionName = "1.1.90"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("boolean", "DIAG_BUILD", diagBuild.toString())
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
}
