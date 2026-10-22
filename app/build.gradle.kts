plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val fireBeatsServerUrl = providers.gradleProperty("FIREBEATS_SERVER_URL")
    .orElse(providers.gradleProperty("FIRELAB_SERVER_URL"))
    .orElse("https://fire-beats.onrender.com")
val showDebugStats = providers.gradleProperty("FIREBEATS_SHOW_DEBUG_STATS")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

android {
    namespace = "io.github.ansellmaximilian.firebeats"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.ansellmaximilian.firebeats"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"

        buildConfigField("String", "SERVER_URL", "\"${fireBeatsServerUrl.get()}\"")
        buildConfigField("boolean", "SHOW_DEBUG_STATS", showDebugStats.get().toString())

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.webrtc.android)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
