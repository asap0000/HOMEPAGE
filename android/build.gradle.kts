// Top-level build file. Plugin versions are declared in gradle/libs.versions.toml
// (Version Catalog) and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
