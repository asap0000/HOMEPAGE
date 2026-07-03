plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.privacycamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.privacycamera"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        // istech バージョニング規約: セマンティックバージョニング3桁 (vMAJOR.MINOR.PATCH)
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing is driven entirely by environment variables (provided by CI
    // from GitHub Secrets, or set locally). The keystore is never committed.
    // If no keystore is available, the release build is simply left unsigned so
    // the build never breaks.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH") ?: "release.keystore"
    val releaseKeystoreFile = file(releaseKeystorePath)
    val hasReleaseSigning = releaseKeystoreFile.exists() &&
        !System.getenv("RELEASE_STORE_PASSWORD").isNullOrEmpty()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                // The release keystore is PKCS12, which uses a single password: the key
                // password is always identical to the store password (a separate -keypass
                // is ignored at creation time). Use the store password directly so signing
                // can't be broken by a stale/incorrect RELEASE_KEY_PASSWORD secret.
                keyPassword = System.getenv("RELEASE_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
