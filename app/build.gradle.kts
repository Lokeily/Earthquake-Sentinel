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

// BeeCLD 令牌：优先读 local.properties（不提交 Git），回退 gradle.properties（默认值）
val beecldToken: String = localProperties.getProperty("beecld.token")
    ?: (project.findProperty("beecld.token") as? String ?: "")

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
        versionCode = 22
        versionName = "1.2.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        // BeeCLD·2v8 令牌：构建时从 Gradle 属性注入 BuildConfig，避免硬编码在源码中
        buildConfigField("String", "BEECLD_TOKEN", "\"$beecldToken\"")
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
