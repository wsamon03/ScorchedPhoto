# Scorched Photo

A native Android "Scorched Earth"-style 2D tank artillery game. Take or pick a photo,
the app automatically figures out the ground (foreground, destructible, tanks stand on
it) versus the sky/backdrop (background), and a turn-based artillery battle plays out
on top of it — local pass-and-play multiplayer and/or CPU opponents, multiple tanks,
a small weapon arsenal.

## Modules

- `:terrain` — plain `kotlin("jvm")`, no Android dependency. The on-device terrain
  segmentation heuristic (`TerrainSegmenter`) that turns a photo into a per-column
  ground-height array (`HeightMap`).
- `:engine` — plain `kotlin("jvm")`, depends on `:terrain`. Projectile physics, crater
  carving, damage, tank placement, turn management, CPU aim AI, weapon catalog.
- `:app` — the Android application: Compose UI, CameraX/Photo Picker, and the
  `SurfaceView`-based real-time game rendering.

`:terrain` and `:engine` intentionally have zero Android dependencies so their logic
is testable on a plain JVM.

## Building

```
./gradlew :terrain:test :engine:test   # pure game logic, no Android SDK required
./gradlew :app:assembleDebug           # full app, requires an installed Android SDK
```

If your environment has no Android SDK (or no network access to Google's Maven
repository, which hosts the Android Gradle Plugin itself), the first command still
works — `:app`'s build script is never configured in that case as long as you pass
`--configure-on-demand` (already the default here via `gradle.properties`). The second
command will fail for that infrastructure reason, not a code-correctness one.

## Manual verification (needs a device/emulator)

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch the app, pick a gallery photo with a clear ground/sky split, confirm the terrain
preview line looks reasonable, start a mixed human + CPU match, fire a few shots with
different weapons, and verify craters carve visually, tanks fall when their ground is
removed, wind changes (and visibly affects trajectory) each turn, and elimination /
victory trigger correctly.
