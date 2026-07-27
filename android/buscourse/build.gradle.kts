// :buscourse — 送迎バス コース記録・案内システム（BusCourse）
// 設計正典: istech/docs/2026-07-08_システム設計_BusCourse.md
//
// オフライン厳守方針（istech/CLAUDE.md・設計書§8）:
//   - INTERNET 権限は宣言しない（AndroidManifest.xml で tools:node="remove" により除去。§5.3.1）。
//   - ネットワーク系ライブラリを新規に直接依存として追加しない。play-services-location も採用しない（D1）。
//   - フェーズ3実装中：MapLibre依存（android-sdk・android-plugin-annotation）を追加済み（設計書§9・§9.1a・§10.3）。
//     okhttp3はandroid-sdk本体のruntime直接依存として推移的にAPKへ同梱される（§5.2.3実測済みの既知事項。
//     補助防御Interceptor実装のため本ファイルでもimplementation明示宣言、§5.5）。AAR自身のINTERNET等
//     5権限宣言も同様に既知事項。「オフライン厳守」はネットワーク系ライブラリを一切含めないことでは
//     なく、実通信を多層で到達不能にすることで担保する：
//       (1) AndroidManifest.xmlのtools:node="remove"でINTERNET等の権限をマージ後マニフェストから除去
//           （§5.3.1、一次防御）。
//       (2) FailClosedNetworkInterceptor（§5.5）がhttp(s)スキームを即時例外化する補助防御。
//       (3) CI Layer1（出荷APKの権限検査）・Layer2（android/ci/offline-allowlist.ymlによる
//           releaseRuntimeClasspath混入ライブラリの理由付き構成管理、§8.2）が機械的に担保する。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Compose（フェーズ2 UI、:app と同じ Kotlin Compose コンパイラプラグイン。バージョンは Version Catalog で一元管理）
    alias(libs.plugins.kotlin.compose)
    // KSP はルートの build.gradle.kts では宣言していないため、ここでバージョンを指定する。
    // Kotlin 2.2.10（ルート宣言）に対応する KSP リリース。バージョンは Version Catalog で一元管理（KSP2正規使用）。
    alias(libs.plugins.ksp)
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

    // ------------------------------------------------------------------------------------------
    // 環境分離（官房 2026-07-26 裁定「再発防止4点」の 1＝構造的免疫。2026-07-27 実装）
    //
    // 事故: 開発中の `adb uninstall` で実機の内部ストレージが消え、実車走行のタイムラプス映像
    // 約14,600枚を失った。禁止リストは言い換え（`adb -s <serial> uninstall` 等）で抜けるため
    // 負け筋であり、**アプリ ID を分けて物理的に届かなくする**のが本命の対策。
    //
    //   debug  … com.istech.buscourse.debug  ← 日常の開発・検証はすべてこちら。消してよい
    //   field  … com.istech.buscourse        ← 記録用実機に入れる。**二度と uninstall しない**
    //   release… com.istech.buscourse        ← 配布用（keystore 未設定のため現状は未使用）
    //
    // field を設ける理由: release 署名の keystore が未設定（CLAUDE.local.md）なので、
    // 「suffix 無し × debug 署名」の枠が別に要る。これなら keystore 未設定のまま今日から効く。
    //
    // ⚠ 運用上の罠（手順書に必ず書くこと）
    //   (a) 救出データ・実車データの復元先は **field 側**（suffix 無し）。debug 側へ置くと
    //       保護対象外の場所に本番データを載せることになり、この分離の意味が消える。
    //   (b) 既存の検証環境（エミュレータ等）の既存データは suffix 無し ID の下にあるため、
    //       debug ビルドからは見えなくなる。検証を続けるなら field ビルドを入れること。
    // ------------------------------------------------------------------------------------------
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("field") {
            // applicationIdSuffix は付けない＝com.istech.buscourse（実データを持つ側）
            isDebuggable = true
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-field"
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

    testOptions {
        unitTests {
            // Robolectric がアプリのリソース/マニフェストを読めるようにする（:app と同じ設定、§後述テスト基盤新設）
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)

    // MapLibre Native SDK本体。okhttp3依存・INTERNET権限宣言を持つがmbtiles://はネイティブ層でOkHttpを
    // 経由しない（設計書§5.2.3）。ネットワーク到達不能化はManifestのtools:node=remove（§5.3.1）と
    // CI Layer1/2（§8）で担保する。
    implementation(libs.maplibre.android.sdk)
    // SymbolManager提供元。androidx.appcompat依存のみでネットワーク系ライブラリ非依存
    // （§5.2.4、2026-07-09実測監査済み）。
    implementation(libs.maplibre.plugin.annotation)
    // 補助防御Interceptor実装（設計書§5.5、D8二次防御、2026-07-12追加）のため okhttp3 型
    // （Interceptor/Response/OkHttpClient）を直接参照する必要がある。okhttp3自体は
    // android-sdk:13.3.1の推移依存として既にAPKに同梱されているが、そのGradle module metadata上は
    // runtime variantのみに現れ（api variantには含まれない）compile classpathへ伝播しないため
    // （実測: android-sdk-13.3.1.moduleのvariant比較）、同一バージョン（POM実測4.12.0）を
    // 明示的にimplementation宣言する。新規のネットワークライブラリを追加するものではなく、
    // 既存の推移依存をコンパイル可視化するのみ。
    implementation(libs.okhttp)

    // Jetpack Compose（フェーズ2 UI、設計書§9）。BOM・各ライブラリのバージョンは :app に合わせる。
    // いずれも androidx の UI ライブラリでありネットワーク系依存を含まない（§8 Layer1 検査で担保）。
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle（BusRecordingService = LifecycleService、設計書§4.1）
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    // Room（設計書§3 正典スキーマ）。KSP2正規使用のためRoom 2.7系（KSP2対応版）に更新（2026-07-12）。
    // バージョンは Version Catalog（gradle/libs.versions.toml）で一元管理。
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // CameraX（設計書§4.5。記録エンジンは Preview 常時表示を採用しないため core/camera2/lifecycle、
    // 停留所カード新規作成画面（フェーズ2、§9）の撮影プレビューにのみ camera-view の PreviewView を使う）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.kotlinx.coroutines.android)
    // ProcessCameraProvider.getInstance(context).await() に必要（設計書§2.3・§4.5.2、CameraCaptureController実装時に使用）
    implementation(libs.kotlinx.coroutines.guava)

    // WorkManager（設計書§4.10.3 StorageRotationWorker）。ローカルタスクスケジューラでネットワーク通信は伴わない。
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore（設計書§4.4、録画中フラグ＋sessionIdの永続化）。ローカルファイルI/Oのみでネットワーク通信は伴わない。
    implementation(libs.androidx.datastore.preferences)

    // 位置情報は android.location.LocationManager / GPS_PROVIDER のみ（D1）。
    // play-services-location は追加しないこと。

    // ---- 単体テスト (JVM実行。APKには一切入らない。②「コース編成(抽出)」解析ロジックの
    //      単体テスト基盤新設、2026-07-14。バージョンは:appのテスト依存・Version Catalogに合わせる) ----
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.room.testing)
}
