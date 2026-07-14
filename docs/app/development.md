# Application Development

## Toolchain

The project pins its build through the Gradle version catalog and wrapper:

- Gradle 8.13;
- Android Gradle Plugin 8.13.2;
- Kotlin Multiplatform and Compose Compiler 2.3.10;
- Compose Multiplatform 1.10.0;
- Java 17 bytecode with JDK 17 or newer to run Gradle;
- Android compile/target SDK 35 and minimum SDK 29 (Android 10).

Install an Android SDK containing platform 35 and Build Tools 35.0.0, then expose it through Android Studio, `ANDROID_HOME`, or an untracked `local.properties` file. A system Gradle or standalone Kotlin installation is not required.

## Modules

`shared/domain` is a Kotlin Multiplatform Android library. `commonMain` contains only platform-neutral domain contracts. The `commonTest` compilation includes deterministic fakes from the separate `commonTestFixtures` directory; they are not packaged in production artifacts. Application `commonTest` uses that same fixture directory rather than maintaining a copy.

`apps/androidApp` is the only shipped target. Its `commonMain` contains the Compose Multiplatform root, while `androidMain` owns the manifest and Android launcher activity. No desktop or iOS target is configured.

## Baseline commands

From a clean checkout:

```bash
./gradlew --version
./gradlew :shared:domain:testDebugUnitTest
./gradlew :apps:androidApp:testDebugUnitTest
./gradlew :apps:androidApp:lintDebug
./gradlew :apps:androidApp:assembleDebug
./gradlew check
```

After dependencies have been downloaded once, add `--offline` to verify that the cached build does not require network access.

The debug APK is written below `apps/androidApp/build/outputs/apk/debug/`. Install it with Android Studio or `adb install -r <apk-path>` and confirm the launcher displays the minimal Categorizer root.

## Dependency boundary

Approved foundation dependencies are Kotlin, Compose Multiplatform, Android Gradle tooling, AndroidX Activity, and their transitive UI/runtime dependencies. Network, authentication, analytics, telemetry, cloud, database, and ONNX libraries are intentionally absent from this foundation.
