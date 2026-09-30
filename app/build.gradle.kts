plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.kuscher.studiosnap"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kuscher.studiosnap"
        minSdk = 34
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.1"
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
    }

    // Release signing config from ~/.config/studiosnap (never committed). Absent -> unsigned.
    val keyDir = File(System.getProperty("user.home"), ".config/studiosnap")
    val keyFile = File(keyDir, "keystore.jks")
    val keyPassFile = File(keyDir, "keystore.pass")
    signingConfigs {
        if (keyFile.exists() && keyPassFile.exists()) {
            create("release") {
                storeFile = keyFile
                val pw = keyPassFile.readText().trim()
                storePassword = pw
                keyAlias = "studiosnap"
                keyPassword = pw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Person segmentation for the camera bubble's cut-out mode: the LiteRT runtime runs Google's
    // selfie segmentation model (assets/models, Apache 2.0) on the CPU. ~11 MB, vs ~41 MB for the
    // ML Kit segmenter. LiteRT 1.x: 2.x adds download-service permissions this app doesn't need.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    testImplementation("junit:junit:4.13.2")
}
