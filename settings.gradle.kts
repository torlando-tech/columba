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
        maven { url = uri("https://jitpack.io") } // For usb-serial-for-android
    }
}

rootProject.name = "columba"
include(":app")
include(":data")
include(":domain")

// LXST-kt is a git submodule — must be initialized before building
val lxstSettings = file("LXST-kt/settings.gradle.kts")
require(lxstSettings.exists()) {
    """
    |LXST-kt submodule not initialized. Run:
    |  git submodule update --init --recursive
    """.trimMargin()
}
includeBuild("LXST-kt") {
    dependencySubstitution {
        substitute(module("tech.torlando:lxst")).using(project(":lxst"))
    }
}
include(":reticulum")
include(":detekt-rules")
include(":screenshot-tests")
