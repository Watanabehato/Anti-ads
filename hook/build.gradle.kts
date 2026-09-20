import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :hook —— Android 库（LSPosed 入口、传感器分发 Hook），仅依赖 :core；Xposed API 只编译期可见
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.antiads.hook"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // 仅编译期：不把框架类打包进 APK
    compileOnly(libs.xposed.api)
    testImplementation(libs.junit)
}
