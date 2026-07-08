// :buscourse — 送迎バス コース記録・案内システム（BusCourse）
// 設計正典: istech/docs/2026-07-08_システム設計_BusCourse.md
//
// オフライン厳守方針（istech/CLAUDE.md・設計書§8）:
//   - INTERNET 権限は宣言しない（AndroidManifest.xml 参照）
//   - ネットワーク系ライブラリ（okhttp/retrofit/ktor-client/volley/grpc/apache.http/cronet）を
//     直接依存・推移依存のいずれにも含めない。play-services-location も採用しない（D1）。
//   - MapLibre はフェーズ3まで追加しない（設計書§9）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // KSP はルートの build.gradle.kts では宣言していないため、ここでバージョンを指定する。
    // Kotlin 2.0.21（ルート宣言）に対応する KSP リリース。
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

android {
    namespace = "com.istech.buscourse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.istech.buscourse"
        minSdk = 26
        targetSdk = 35
        // istech バージョニング規約: リリースタグは vMAJOR.MINOR.PATCH の3桁 semver。
        // :app と同様、配布ビルドでは CI が VERSION_CODE / VERSION_NAME を注入する想定。
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.0-dev"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")

    // Lifecycle（BusRecordingService = LifecycleService、設計書§4.1）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // Room（設計書§3 正典スキーマ）
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // CameraX（設計書§4.5。camera-view/Preview 常時表示は採用しない設計のため core/camera2/lifecycle のみ）
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 位置情報は android.location.LocationManager / GPS_PROVIDER のみ（D1）。
    // play-services-location は追加しないこと。
}
