plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 固定签名密钥：保证每次 CI 构建的 APK 签名一致，可以覆盖升级。
 * （原先每次由 CI 重新生成 debug 签名 → 每个版本签名不同 → “签名冲突”。）
 */
val stableKeystore = rootProject.file("keystore/spatial-scene.p12")

android {
    namespace = "com.danxing.spatialscene"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.danxing.spatialscene"
        minSdk = 29
        targetSdk = 35
        versionCode = 16
        versionName = "0.9.4"

        ndk {
            // 现代 Android 设备均为 arm64；只保留 arm64 可显著减小 APK
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (stableKeystore.exists()) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = "spatialscene"
                keyAlias = "spatialscene"
                keyPassword = "spatialscene"
            }
        }
    }

    buildTypes {
        debug {
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // ONNX Runtime 需要以未压缩方式 mmap assets 中的模型
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
