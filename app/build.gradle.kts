import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :app —— 宿主 APK（管理界面、配置存储、Provider、状态聚合），同时承载 LSPosed 模块入口
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.antiads.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.antiads.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-skeleton"
        // 仅 androidTest 作用域的基础设施；产品运行依赖不包含 androidx.test
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 无 debug applicationIdSuffix：Provider authority 必须稳定为 com.antiads.app.config
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // v1 默认关闭压缩，避免反射入口被移除；开启前须另加 keep 规则并复测
            isMinifyEnabled = false
            isShrinkResources = false
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
    implementation(project(":accessibility"))
    implementation(project(":hook"))
    // 纯 JVM 单测（t8 的 :app:testDebugUnitTest 需要真实存在的测试源）；
    // 不打开 android.testOptions.unitTests.returnDefaultValues，避免把 Android API 桩成功
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
