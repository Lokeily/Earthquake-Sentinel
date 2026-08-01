import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) load(FileInputStream(localFile))
}

android {
    signingConfigs {
        create("release") {
            val keystorePath = localProperties.getProperty("dianguard.keystorePath")
                ?: (rootProject.projectDir.path + "/dianguard-release.jks")
            storeFile = file(keystorePath)
            storePassword = localProperties.getProperty("dianguard.storePassword")
            keyAlias = localProperties.getProperty("dianguard.keyAlias")
            keyPassword = localProperties.getProperty("dianguard.keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    namespace = "com.dianguard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dianguard.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 19
        versionName = "1.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs["release"]
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        // 离线构建无法拉取 lint-gradle，关闭 release 的阻断式 lint，仅影响本地的签名构建校验
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    // WebSocket / HTTP 客户端，用于订阅震前预警（EEW）数据流
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
