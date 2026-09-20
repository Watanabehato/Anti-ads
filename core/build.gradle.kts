import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core —— 纯 Kotlin/JVM，禁止依赖 Android/Context/Binder/Xposed 或其他工程模块
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 公共类型经 api 暴露，其他模块不各自实现协议编解码
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // core 为纯 JVM 单测，不引入 Android
    useJUnit()
}
