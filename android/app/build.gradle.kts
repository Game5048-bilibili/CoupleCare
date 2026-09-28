plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // namespace 与 applicationId 必须一致：net.game5048.baobao
    namespace = "net.game5048.baobao"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.game5048.baobao"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }

        // ---------------- 高德地图 Key ----------------
        // 三种配置方式，优先级从高到低：
        //   1) 命令行： ./gradlew assembleDebug -PAMAP_API_KEY=你的key
        //   2) android/gradle.properties 里写 AMAP_API_KEY=你的key（推荐）
        //   3) 环境变量 ORG_GRADLE_PROJECT_AMAP_API_KEY=你的key
        // 没配置也能编译，但地图会显示「INVALID_USER_KEY」，见 README 第 6 节。
        val amapKey = (project.findProperty("AMAP_API_KEY") as String?)?.trim().orEmpty()
        buildConfigField("String", "AMAP_API_KEY", "\"$amapKey\"")
        // 高德 SDK 从 Manifest 的 meta-data 读 Key，这里用占位符注入
        manifestPlaceholders["amapApiKey"] = amapKey
    }

    buildTypes {
        debug {
            // debug 包保持与 release 相同的 applicationId，严格遵守
            // 「namespace 与 applicationId 均为 net.game5048.baobao」的要求，
            // 这样两台手机装 debug 或 release 行为完全一致。
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false // 个人使用，先关混淆，避免高德 SDK 的反射被裁掉
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 未配置签名时 assembleRelease 会产出未签名 APK，
            // 用 README 里的 keytool/Android Studio 生成签名包。
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // ---- Compose BOM：统一管理 compose 各库版本 ----
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))

    // ---- 基础 ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")

    // ---- Lifecycle / ViewModel / Compose 互操作 ----
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    // ---- Compose UI / Material 3 ----
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // ---- 协程（原生 Socket，不用 Retrofit / OkHttp） ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- 地图：高德地图 ----
    // 国内直连、无需 Google 服务框架、无需梯子。Key 与「包名 + 签名 SHA1」绑定。
    // 版本号如果拉不到，去这里查最新的：
    //   https://lbs.amap.com/api/android-sdk/guide/create-project/android-studio-create-project
    // 然后改成本行对应的版本即可。
    implementation("com.amap.api:3dmap:10.0.600")

    // ---- 定位：Google Play Services FusedLocationProvider ----
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // ---- 本地密钥/偏好加密存储（可选，用于加密 SharedPreferences） ----
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
