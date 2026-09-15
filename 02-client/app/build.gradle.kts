plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.locationshare.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.locationshare.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.8.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    // 本机 GPS
    // Activity Result API
    implementation("androidx.activity:activity-ktx:1.8.2")
    // 地图（OpenStreetMap，无需 Google API Key）
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
