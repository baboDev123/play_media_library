import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Device tests of the whole engine against real media; not published.
plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "io.github.playme.media.tests"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
        }
    }
}

dependencies {
    androidTestImplementation(project(":media-decoder"))
    androidTestImplementation(project(":media-info"))
    androidTestImplementation(project(":media-extractor"))
    androidTestImplementation(libs.androidx.media3.exoplayer)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
