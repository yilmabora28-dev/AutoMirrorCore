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
        maven("https://maven.pkg.github.com/nickthecoder/android-car-app") {
            credentials { /* public read */ }
        }
    }
}

rootProject.name = "AutoMirrorCore"
include(":app")
