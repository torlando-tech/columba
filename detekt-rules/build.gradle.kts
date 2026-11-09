plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:1.23.8")
    // kotlin-compiler-embeddable needs coroutines at runtime; the uiTextDump
    // JavaExec classpath is compileClasspath, so it must be declared here.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:1.23.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Gradle 9 requires the launcher to be on the test runtime classpath explicitly
    // since `useJUnitPlatform()` no longer ships it transitively.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Sink-equivalence dumper (i18n ticket 23). Usage:
//   ./gradlew :detekt-rules:uiTextDump -PdumpRoot=<repoRoot> -PdumpOut=<file> [-PdumpFiles=a.kt,b.kt]
tasks.register<JavaExec>("uiTextDump") {
    group = "verification"
    description = "Dump ordered UI-text skeletons for the sink-equivalence gate"
    mainClass.set("network.columba.app.detekt.dump.MainKt")
    classpath = files(sourceSets.main.get().output, configurations.compileClasspath.get())
    workingDir = rootDir
    argumentProviders +=
        CommandLineArgumentProvider {
            val root = (project.findProperty("dumpRoot") as String?) ?: "."
            val out = (project.findProperty("dumpOut") as String?) ?: error("usage: -PdumpOut=<file>")
            val files = (project.findProperty("dumpFiles") as String?)
            buildList {
                add("--root"); add(root)
                add("--out"); add(out)
                if (!files.isNullOrBlank()) { add("--files"); add(files) }
            }
        }
}
