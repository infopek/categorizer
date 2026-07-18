import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:application"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
        commonTest {
            kotlin.srcDir(rootProject.file("shared/domain/src/commonTestFixtures/kotlin"))
            dependencies {
                implementation(kotlin("test"))
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core)
            implementation(libs.onnxruntime.android)
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}

android {
    namespace = "categorizer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.infopek.categorizer"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].assets.srcDir(rootProject.file("ml/catalog"))
    sourceSets["androidTest"].assets.srcDir("src/androidInstrumentedTest/assets")
    providers.gradleProperty("benchmarkAssetDir").orNull?.let {
        sourceSets["androidTest"].assets.srcDir(it)
    }
    val configuredRecognitionAssets = providers.gradleProperty("recognitionAssetRoot").orNull?.let(rootProject::file)
    val localRecognitionAssets = rootProject.file("ml/artifacts/lepidoptera/android-assets")
    val recognitionAssets = configuredRecognitionAssets ?: localRecognitionAssets.takeIf(File::isDirectory)
    recognitionAssets?.let { sourceSets["main"].assets.srcDir(it) }

    tasks.register("verifyReleaseRecognitionBundle") {
        group = "verification"
        description = "Validates the offline recognition bundle for a release build."
        doLast {
            val bundle = requireNotNull(recognitionAssets) {
                "Release builds require recognition/model.onnx. Export it under ml/artifacts/lepidoptera/android-assets or pass -PrecognitionAssetRoot=<absolute-directory>."
            }
            val recognition = bundle.resolve("recognition")
            val model = recognition.resolve("model.onnx")
            val manifestFile = recognition.resolve("model-manifest.json")
            val classMap = recognition.resolve("class-map.json")
            require(model.isFile) { "Release recognition model is missing." }
            require(manifestFile.isFile) { "Release recognition manifest is missing." }
            require(classMap.isFile) { "Release recognition class map is missing." }

            @Suppress("UNCHECKED_CAST")
            val manifest = JsonSlurper().parse(manifestFile) as Map<String, Any>
            val modelMetadata = manifest["model"] as? Map<*, *>
                ?: error("Release recognition manifest has no model metadata.")
            val classMapMetadata = manifest["class_map"] as? Map<*, *>
                ?: error("Release recognition manifest has no class-map metadata.")
            fun sha256(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }

            require(model.length() == (modelMetadata["size_bytes"] as? Number)?.toLong()) {
                "Release recognition model size does not match its manifest."
            }
            require(sha256(model) == modelMetadata["sha256"]) {
                "Release recognition model SHA-256 does not match its manifest."
            }
            require(sha256(classMap) == classMapMetadata["sha256"]) {
                "Release recognition class-map SHA-256 does not match its manifest."
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn("verifyReleaseRecognitionBundle")
}
