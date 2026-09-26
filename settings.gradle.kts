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
    }
}

rootProject.name = "StreamHub"
include(":app")

// SoundHub: a separate music app (Soulseek + Dolby Atmos passthrough) living in soundhub/.
include(":soundhub", ":soundhub-core")
project(":soundhub").projectDir = file("soundhub/app")
project(":soundhub-core").projectDir = file("soundhub/core")
