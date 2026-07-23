plugins {
    `java-library`
}

group = "dev.fforj"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

// Target Java 21 LTS (ADR-3): the toolchain compiles and runs tests on a newer JDK,
// but --release 21 checks all code against the Java 21 API and emits Java 21 class
// files, so the published jar runs on every JDK from 21 up. No preview features —
// Scopes (StructuredTaskScope, JEP 505) is shelved on branch poc/scopes-jep505 until
// the API finalizes.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("source", "21")
    // House style is "a short paragraph on intent" per public member, not exhaustive
    // @param/@return tags — keep doclint's real checks but drop the 'missing' group.
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

repositories {
    mavenCentral()
}

dependencies {
    // Production code: ZERO dependencies. Only the standard library.
    // If you ever add one, write the ADR first.

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
