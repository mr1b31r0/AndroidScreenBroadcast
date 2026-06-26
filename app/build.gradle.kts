plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.athena.democaster"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.athena.democaster"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // QR scanning — ZXing embedded (provides a ready-made scan Activity via Intent)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // WebSocket — OkHttp (SOCKS5 proxy for Tor routing)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
