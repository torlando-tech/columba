// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
    id("com.chaquo.python") version "16.0.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
    id("jacoco")
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("de.aaschmid.cpd") version "3.5"
}

// Apply JaCoCo, ktlint, and detekt to all subprojects (except detekt-rules)
subprojects {
    // Skip detekt-rules module - it's a pure JVM module for detekt custom rules
    if (name == "detekt-rules") return@subprojects

    apply(plugin = "jacoco")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.14"
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.0.1")
        android.set(true)
        outputColorName.set("RED")
        // Currently advisory - there are pre-existing style violations (primarily Compose
        // function naming conventions which conflict with ktlint defaults).
        // Run `./gradlew ktlintCheck` to see violations; contributions to fix them are welcome.
        ignoreFailures.set(true)
        filter {
            exclude("**/generated/**")
            exclude("**/build/**")
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("${rootProject.projectDir}/detekt-config.yml"))
        // Baseline captures pre-existing issues. New code must pass all checks.
        // Run `./gradlew detektBaseline` to update after intentional changes.
        baseline = file("$projectDir/detekt-baseline.xml")
    }

    // Add custom Columba detekt rules
    dependencies {
        "detektPlugins"(project(":detekt-rules"))
    }
}

// CPD (Copy-Paste Detector) for duplicate code detection
cpd {
    language = "kotlin"
    minimumTokenCount = 100 // ~10-15 lines minimum for duplicate detection
    toolVersion = "7.7.0" // PMD version with Kotlin support
}

tasks.named<de.aaschmid.gradle.plugins.cpd.Cpd>("cpdCheck") {
    // Configure source files for all modules
    source =
        files(
            "app/src/main/java",
            "data/src/main/java",
            "domain/src/main/kotlin",
            "reticulum/src/main/java",
        ).asFileTree.matching {
            include("**/*.kt")
            exclude("**/generated/**")
            exclude("**/build/**")
        }
    // Advisory mode initially - duplicates are reported but don't fail the build
    ignoreFailures = true
    // Enable text report for easier reading
    reports {
        text.required.set(true)
    }
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}

// Create unified coverage report task
tasks.register("jacocoTestReport", JacocoReport::class) {
    group = "verification"
    description = "Generate unified Jacoco coverage report for all modules"

    // Only depend on debug unit tests to avoid Robolectric issues with release builds
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("testDebugUnitTest") })

    // Use lazy configuration - fileTree is resolved at execution time, not registration time
    val sourceDirectoriesList = mutableListOf<File>()
    val classDirectoriesList = mutableListOf<File>()
    val execDataPatterns = mutableListOf<String>()

    subprojects.forEach { subproject ->
        // Add source directories (these exist at registration time)
        val sourceDir = subproject.file("src/main/java")
        if (sourceDir.exists()) {
            sourceDirectoriesList.add(sourceDir)
        }
        val kotlinSourceDir = subproject.file("src/main/kotlin")
        if (kotlinSourceDir.exists()) {
            sourceDirectoriesList.add(kotlinSourceDir)
        }

        // Add patterns for class directories and exec data (resolved at execution time)
        val buildDir = subproject.layout.buildDirectory.get().asFile
        // Use ASM-transformed classes which contain all classes including UI/Compose
        classDirectoriesList.add(File("$buildDir/intermediates/classes/debug/transformDebugClassesWithAsm/dirs"))
        // Android puts coverage data in outputs/unit_test_code_coverage/
        execDataPatterns.add("$buildDir/outputs/unit_test_code_coverage/debugUnitTest")
    }

    sourceDirectories.setFrom(sourceDirectoriesList)

    // Use provider for lazy evaluation - resolved at execution time
    classDirectories.setFrom(
        provider {
            classDirectoriesList.filter { it.exists() }.map { dir ->
                fileTree(dir) {
                    exclude(
                        "**/R.class",
                        "**/R\$*.class",
                        "**/BuildConfig.*",
                        "**/Manifest*.*",
                        "**/*Test*.*",
                        "**/Hilt_*.*",
                        "**/*_Factory.*",
                        "**/*_MembersInjector.*",
                    )
                }
            }
        },
    )

    // Use provider for lazy evaluation - resolved at execution time
    executionData.setFrom(
        provider {
            execDataPatterns.mapNotNull { dirPath ->
                val execDir = File(dirPath)
                if (execDir.exists()) {
                    fileTree(execDir) { include("*.exec") }
                } else {
                    null
                }
            }
        },
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}
