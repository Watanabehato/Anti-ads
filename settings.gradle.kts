// Anti-ads 五模块工程（骨架阶段由 t2 建立；并行期间共享文件只由顺序集成变更）
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 仅 Xposed API 走官方仓库；其他依赖不使用该源
        maven("https://api.xposed.info/") {
            content { includeGroup("de.robv.android.xposed") }
        }
    }
}

rootProject.name = "Anti-ads"

include(":app")
include(":core")
include(":accessibility")
include(":hook")
include(":probe")
