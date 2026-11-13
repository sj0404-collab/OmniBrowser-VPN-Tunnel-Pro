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
        // Библиотека Psiphon (ca.psiphon:psiphontunnel) публикуется в репозитории на GitHub
        maven { url = uri("https://raw.github.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master") }
    }
}

rootProject.name = "WindowsWasm64"
include(":app")
// v4.87.0: движок и интерфейс RupoopFork входят в наш APK одним модулем
include(":rupoop")
