plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aegis.health"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aegis.health"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Keep model/DB files uncompressed so they can be mmap'd
    androidResources {
        noCompress += listOf("gguf", "litertlm", "tflite", "sqlite", "db")
    }
}

dependencies {
    // Compose BOM — pin all Compose libs to a single compatible version
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("com.valentinilk.shimmer:compose-shimmer:1.4.0")

    // Core / Activity / Lifecycle
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // LiteRT-LM on-device inference with GPU acceleration on Adreno/Mali/
    // Xclipse. 0.10.2 is required for the .litertlm artifact on-device;
    // 0.10.0 initializes the bundle but crashes natively during first prefill.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")

    // CameraX
    val cameraXVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // ML Kit — text recognition (offline, bundled model)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // PdfBox-Android — Apache 2.0 pure-Java port of PDFBox 2.0.27. No INTERNET
    // permission declared (verified Plan 01-08). PDFBoxResourceLoader.init(ctx)
    // may be required at app start; spike (Plan 01-08) verifies empirically.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Stock Android SQLite is used directly — no SQLCipher wrapper.
    // The KB contains only public-domain FDA/NLM data; encryption adds
    // no real security since the passphrase would have to ship in the APK.

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Room — local persistence for run history (separate DB from KBDatabase).
    val roomVersion = "2.7.2"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
