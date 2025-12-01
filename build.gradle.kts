// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.chaquo.python") version "16.0.0" apply false
    id("jacoco")
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

// Apply JaCoCo, ktlint, and detekt to all subprojects
subprojects {
    apply(plugin = "jacoco")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
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
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}

// Create unified coverage report task
tasks.register("jacocoTestReport", JacocoReport::class) {
    group = "verification"
    description = "Generate unified Jacoco coverage report for all modules"

    dependsOn(subprojects.map { it.tasks.withType<Test>() })

    val sourceDirs = files()
    val classDirs = files()
    val executionData = files()

    subprojects.forEach { subproject ->
        subproject.plugins.withType<JavaPlugin> {
            val sourceDir = subproject.file("src/main/java")
            if (sourceDir.exists()) {
                sourceDirs.from(sourceDir)
            }

            val kotlinSourceDir = subproject.file("src/main/kotlin")
            if (kotlinSourceDir.exists()) {
                sourceDirs.from(kotlinSourceDir)
            }

            val buildDir = subproject.layout.buildDirectory.get().asFile
            val classDir = subproject.file("$buildDir/intermediates/javac/debug/classes")
            if (classDir.exists()) {
                classDirs.from(classDir)
            }

            val kotlinClassDir = subproject.file("$buildDir/tmp/kotlin-classes/debug")
            if (kotlinClassDir.exists()) {
                classDirs.from(kotlinClassDir)
            }

            // Collect all JaCoCo execution data files (all test variants)
            val jacocoDir = subproject.file("$buildDir/jacoco")
            if (jacocoDir.exists()) {
                jacocoDir.listFiles()?.filter { it.extension == "exec" }?.forEach { execFile ->
                    executionData.from(execFile)
                }
            }
        }
    }

    sourceDirectories.setFrom(sourceDirs)
    classDirectories.setFrom(classDirs)
    executionData.setFrom(executionData)

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}
