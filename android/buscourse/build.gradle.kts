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
    // Compose（フェーズ2 UI、:app と同じ Kotlin Compose コンパイラプラグイン。バージョンはルートで宣言済み）
    id("org.jetbrains.kotlin.plugin.compose")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")

    // Jetpack Compose（フェーズ2 UI、設計書§9）。BOM・各ライブラリのバージョンは :app に合わせる。
    // いずれも androidx の UI ライブラリでありネットワーク系依存を含まない（§8 Layer1 検査で担保）。
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle（BusRecordingService = LifecycleService、設計書§4.1）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // Room（設計書§3 正典スキーマ）
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // CameraX（設計書§4.5。記録エンジンは Preview 常時表示を採用しないため core/camera2/lifecycle、
    // 停留所カード新規作成画面（フェーズ2、§9）の撮影プレビューにのみ camera-view の PreviewView を使う）
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // ProcessCameraProvider.getInstance(context).await() に必要（設計書§2.3・§4.5.2、CameraCaptureController実装時に使用）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    // WorkManager（設計書§4.10.3 StorageRotationWorker）。ローカルタスクスケジューラでネットワーク通信は伴わない。
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // DataStore（設計書§4.4、録画中フラグ＋sessionIdの永続化）。ローカルファイルI/Oのみでネットワーク通信は伴わない。
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 位置情報は android.location.LocationManager / GPS_PROVIDER のみ（D1）。
    // play-services-location は追加しないこと。
}
