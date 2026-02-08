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

// LXST-kt is a git submodule — must be initialized AND populated before building.
// Check for module build file (not just settings.gradle.kts) since `git submodule init`
// without `update` creates the directory but not the source files.
val lxstModule = file("LXST-kt/lxst/build.gradle.kts")
require(lxstModule.exists()) {
    """
    |LXST-kt submodule not populated. Run:
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
