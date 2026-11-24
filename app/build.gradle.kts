import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) load(FileInputStream(localFile))
}

// 签名凭据来源（P0-1 安全修复：密码从 local.properties 迁移到独立的 secrets.properties，
// 该文件已加入 .gitignore。CI / 发布机仍优先走环境变量，不落任何文件）。
val secretsProperties = Properties().apply {
    val secretsFile = rootProject.file("secrets.properties")
    if (secretsFile.exists()) load(FileInputStream(secretsFile))
}

fun signingCredential(envName: String, propName: String): String? =
    System.getenv(envName) ?: secretsProperties.getProperty(propName)

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
            storePassword = signingCredential("DIANGUARD_STORE_PASSWORD", "dianguard.storePassword")
            keyAlias = env["DIANGUARD_KEY_ALIAS"]
                ?: localProperties.getProperty("dianguard.keyAlias")
            keyPassword = signingCredential("DIANGUARD_KEY_PASSWORD", "dianguard.keyPassword")
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
        // v1.4.5 演练控制台源；v1.4.6 手动/后台下发经纬度与状态双向同步；
        // v1.4.7 真实预警经后台中转（BeeCLD Key 仅存后台，公开 release 包不装配任何密钥）；
        // v1.4.8 Liquid Glass 视觉统一：玻璃卡片/环境光背景/动效时长 token/涟漪与触觉反馈；
        // v1.4.9 背景适配与启动屏修正；
        // v1.4.10 服务器时钟校准：后台帧带 serverTime，倒计时用服务端时间轴，手机时钟漂移
        //         不再造成大屏与手机倒计时不同值（生命安全级一致性）
        versionCode = 36
        versionName = "1.4.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "IS_DEBUG", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs["release"]
            buildConfigField("boolean", "IS_DEBUG", "false")
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
        // 发布版也执行 lint 检查（原 checkReleaseBuilds=false 会跳过发布验证），
        // abortOnError=false：lint 错误阻断、致命错误才阻断；warning 单独看。
        checkReleaseBuilds = true
        abortOnError = true
        // 明确豁免的检查（均属可接受取舍，不掩盖真实问题）：
        // - HardcodedText/SetTextI18n：本项目为中文单语应用，UI 文案以中文字面量直接呈现，
        //   暂未引入多语言资源体系（对外发布语言即中文）；若后续多语言化再迁移到 strings。
        // - IconLauncherShape/IconDuplicates：启动图标为设计资源，形状/去重由素材团队把控，
        //   非逻辑缺陷。警告不影响功能。
        // - TypographyDashes：中文字面量中的连字符属排版偏好，非错误。
        // - InlinedApi：部分新 API 常量（如 FGS type）经 ServiceCompat 兼容处理、或已用
        //   Build.VERSION 守卫，不会被旧系统调用，无崩溃风险。
        // - BatteryLife：REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 仅作为「引导用户自主开启」的
        //   按钮入口，不自动申请，符合自部署场景（非 Play 分发策略考量）。
        disable += setOf(
            "HardcodedText",
            "SetTextI18n",
            "IconLauncherShape",
            "IconDuplicates",
            "TypographyDashes",
            "InlinedApi",
            "BatteryLife"
        )
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    // 协程 + 生命周期协程（P1-2 迁移：AppEvents SharedFlow 事件总线 + lifecycleScope 订阅）
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // WebSocket / HTTP 客户端
    implementation(libs.okhttp)
    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 真实 org.json 实现：覆盖 android.jar 桩，使 parseEew 单元测试能真正解析报文
    testImplementation(libs.org.json)

    // 仪表化测试（P1-1：真机/模拟器上的 UI 与服务生命周期验证）
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
