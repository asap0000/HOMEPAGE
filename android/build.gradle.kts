// Top-level build file. Plugin versions are declared here and applied per-module.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Screenshot/golden testing (JVM, Robolectric-based). Provides the
    // record/verifyRoborazzi<Variant> tasks used by CI and the record-goldens workflow.
    id("io.github.takahirom.roborazzi") version "1.26.0" apply false
}
