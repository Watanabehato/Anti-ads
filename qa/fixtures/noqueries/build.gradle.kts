plugins {
    id("com.android.application") version "8.7.2"
}

android {
    namespace = "com.antiads.fixture.target"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.antiads.fixture.target"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-qa"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
    }
}
// Intentionally no dependencies: this target must not inherit product manifests,
// Provider visibility declarations, Kotlin, AndroidX, or the protection policy.
