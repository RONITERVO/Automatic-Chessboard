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
        exclusiveContent {
            forRepository { maven(url = "https://jitpack.io") }
            filter { includeModule("com.github.bhlangonijr", "chesslib") }
        }
    }
}

rootProject.name = "OpenAutomaticChessboardMobile"
include(":app")
