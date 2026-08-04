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
            // 签名凭据解析优先级（R5 安全修复：避免密码明文落盘）：
            // 1) 环境变量（CI / 发布机注入，不落任何文件）→ 2) local.properties（本地开发，.gitignore 已忽略）
            val env = System.getenv()
            val keystorePath = env["DIANGUARD_KEYSTORE_PATH"]
                ?: localProperties.getProperty("dianguard.keystorePath")
                ?: (rootProject.projectDir.path + "/dianguard-release.jks")
            storeFile = file(keystorePath)
            storePassword = env["DIANGUARD_STORE_PASSWORD"]
                ?: localProperties.getProperty("dianguard.storePassword")
            keyAlias = env["DIANGUARD_KEY_ALIAS"]
                ?: localProperties.getProperty("dianguard.keyAlias")
            keyPassword = env["DIANGUARD_KEY_PASSWORD"]
                ?: localProperties.getProperty("dianguard.keyPassword")
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
        versionCode = 23
        versionName = "1.3.0"

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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    // WebSocket / HTTP 客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 单元测试
    testImplementation("junit:junit:4.13.2")
    // 真实 org.json 实现：覆盖 android.jar 桩，使 parseEew 单元测试能真正解析报文
    testImplementation("org.json:json:20231013")
}
