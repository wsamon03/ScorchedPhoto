// Deliberately does NOT declare com.android.application/library or kotlin.android here.
// AGP as apply-false still forces Gradle to resolve it while configuring the root
// project, which would make every task in this build - including :terrain:test/
// :engine:test - depend on reaching Google's Maven repository for AGP. Declaring
// kotlin.android (KGP) at the root *without* the Android plugin also present there is a
// known Kotlin Gradle Plugin bug (KT-57162): it throws "Could not generate a decorated
// class for type KotlinAndroidTarget > com/android/build/gradle/api/BaseVariant" when a
// subproject then applies both plugins for real, exactly the failure this project hit in
// CI. Both AGP and kotlin.android are declared directly in app/build.gradle.kts instead,
// so the pure-JVM :terrain/:engine modules stay buildable and testable even in
// environments without Android SDK/Google Maven access, and :app's plugin application
// doesn't collide with a partial root-level KGP initialization.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android.gradle) apply false
}
