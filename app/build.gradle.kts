// java 在 Kotlin DSL 里是 Gradle 的 JavaPluginExtension，读 Properties 必须显式导入
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flux.m3u8"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flux.m3u8"
        minSdk = 26          // Android 8.0+：前台服务通知渠道从这一版开始强制
        targetSdk = 34
        versionCode = 1
        versionName = "1.2.1"

        // 只打中文资源，减小 APK 体积
        resourceConfigurations += setOf("zh", "en")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Release 签名 ──
    // 密钥信息写在仓库根目录的 keystore.properties（.gitignore 已排除，不会进仓库）：
    //   storeFile=flux.jks            相对仓库根目录，也可写绝对路径
    //   storePassword=你的密钥库口令
    //   keyAlias=flux
    //   keyPassword=你的密钥口令
    // 该文件不存在时自动跳过签名，仍会构建出 app-release-unsigned.apk，
    // 这样 CI 云编译和克隆代码的人都不会因为缺密钥而构建失败。
    val keystoreProps = Properties().apply {
        val propFile = rootProject.file("keystore.properties")
        if (propFile.exists()) propFile.inputStream().use { load(it) }
    }
    val signingKeyFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }

    signingConfigs {
        if (signingKeyFile != null && signingKeyFile.exists()) {
            create("release") {
                storeFile = signingKeyFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有 keystore.properties 就用它签名；没有则保持未签名（不破坏 CI）
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 单测里若误触到 Android 桩方法，返回默认值而不是直接抛异常
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // mockwebserver / junit 与 okhttp 的重复 LICENSE，不排会打包失败
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.media3.common.util.UnstableApi"
        )
    }

    buildFeatures {
        compose = true
        // AGP 8 起默认不生成 BuildConfig；打开它才能让界面用
        // BuildConfig.VERSION_NAME 读上面的 versionName，避免版本号硬编码在代码里
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // ── AndroidX 核心 ──
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ── Compose（BOM 统一版本）──
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ── 网络与并发 ──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ── 播放（边下边播）──
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // ── 转 MP4（ffmpeg 流拷贝优先，失败降级重编码）──
    // 原版 ffmpeg-kit（com.arthenica）已从 Maven Central 下架，
    // 用社区重建版（io.github.maitrungduc1410），API 包名不变仍是 com.arthenica.ffmpegkit
    implementation("io.github.maitrungduc1410:ffmpeg-kit-min:7.1.5")

    // ── 调试 ──
    // 上面 Compose 段已声明 ui-tooling / ui-test-manifest，这里不再重复

    // ── 测试 ──
    // 纯 JVM 单测：解析器、解密、格式化、限速、播放列表生成等无 Android 依赖的逻辑
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // 仪器化测试：数据库迁移、目录校验、本地播放服务、端到端下载流程
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // BOM 只作用于声明它的那个 configuration，androidTest 必须再声明一次，
    // 否则下面不带版本号的 ui-test-junit4 解析不到版本（"Could not find ...:."）
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
