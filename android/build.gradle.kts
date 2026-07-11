// Top-level build file. Plugin versions are declared in gradle/libs.versions.toml
// (Version Catalog) and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Screenshot/golden testing (JVM, Robolectric-based). Provides the
    // record/verifyRoborazzi<Variant> tasks used by CI and the record-goldens workflow.
    alias(libs.plugins.roborazzi) apply false
}
