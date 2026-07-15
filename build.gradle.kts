// Deliberately does NOT declare com.android.application/library here (even as apply
// false still forces Gradle to resolve the plugin while configuring the root project,
// which would make every task in this build - including :terrain:test/:engine:test -
// depend on reaching Google's Maven repository for AGP). AGP is declared directly in
// app/build.gradle.kts instead, so the pure-JVM :terrain/:engine modules stay buildable
// and testable even in environments without Android SDK/Google Maven access.
plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android.gradle) apply false
}
