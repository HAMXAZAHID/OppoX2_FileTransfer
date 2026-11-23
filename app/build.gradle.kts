plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.hamza.watchfiles"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hamza.watchfiles"
        minSdk = 30 // Wear OS 3+; the OPPO Watch X2 ships with Wear OS 5 (API 34)
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
        // One fixed key so every build installs over the previous one with `adb install -r`.
        // This app is only sideloaded, so the key lives in the repo. Pass -PsigningStore=... etc.
        // (or the matching env vars) to sign with a private key instead.
        create("sideload") {
            storeFile = rootProject.file(
                (findProperty("signingStore") as String?) ?: System.getenv("SIGNING_STORE") ?: "keystore/homefiles-sideload.jks"
            )
            storePassword = (findProperty("signingPassword") as String?) ?: System.getenv("SIGNING_PASSWORD") ?: "homefiles-sideload"
            keyAlias = (findProperty("signingAlias") as String?) ?: System.getenv("SIGNING_ALIAS") ?: "homefiles"
            keyPassword = (findProperty("signingPassword") as String?) ?: System.getenv("SIGNING_PASSWORD") ?: "homefiles-sideload"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(project(":smbcore"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
