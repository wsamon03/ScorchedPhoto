// Deliberately empty. Every subproject applies its own plugins directly (never
// `apply false`), and their versions are pinned once in gradle/libs.versions.toml, so
// there's nothing for the root project to pre-declare.
//
// Two real bugs were hit by putting plugins here instead:
//   1. AGP (com.android.application/library), even as apply-false, forces Gradle to
//      resolve it while configuring the *root* project - which would make every task in
//      this build, including :terrain:test/:engine:test, depend on reaching Google's
//      Maven repository for AGP. :terrain/:engine are plain kotlin("jvm") modules that
//      never need AGP at all.
//   2. Declaring org.jetbrains.kotlin.android (KGP) at the root without an Android
//      plugin also present there is a known Kotlin Gradle Plugin bug (KT-57162): it
//      throws "Could not generate a decorated class for type KotlinAndroidTarget >
//      com/android/build/gradle/api/BaseVariant" once a subproject applies both plugins
//      for real. Removing that fixed the BaseVariant crash, but left a second-order
//      version of the same class of bug: org.jetbrains.kotlin.jvm and
//      org.jetbrains.kotlin.android share the same underlying Kotlin Gradle Plugin
//      artifact, so leaving kotlin.jvm declared here (for :terrain/:engine) made
//      Gradle report kotlin.android as "already on the classpath with an unknown
//      version" when :app applied it directly. Root now declares no plugins at all.
