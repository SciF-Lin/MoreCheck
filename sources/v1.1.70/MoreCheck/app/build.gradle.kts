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
        versionCode = 26
        versionName = "1.1.70"
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
    debugImplementation(libs.androidx.ui.tooling)
}
