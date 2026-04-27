plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:1.23.8")

    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:1.23.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Gradle 9 requires the launcher to be on the test runtime classpath explicitly
    // since `useJUnitPlatform()` no longer ships it transitively.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
