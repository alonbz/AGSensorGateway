plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agsense.ksensorgateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.agsense.ksensorgateway"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.43"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Barcode/QR scanning to auto-fill a sensor's MAC (BarcodeScanActivity):
    // camera preview via CameraX, decoding via ML Kit's on-device Barcode
    // Scanning API (no network call, no Google Play Services account
    // needed — the model ships in the APK / is fetched by Play Services
    // as an on-device module, either way nothing leaves the phone).
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
