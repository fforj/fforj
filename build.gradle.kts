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

// Enable preview features. StructuredTaskScope (JEP 505) is still a PREVIEW API in
// Java 25, so --enable-preview is genuinely required to compile Scopes. Consequence
// for users: Scopes.class is preview-flagged — loading it requires Java 25 exactly,
// with --enable-preview. The other classes (Result, Validated, NonEmptyList, Retry)
// are not flagged and load normally. Remove this when the API finalizes.
val previewArgs = listOf("--enable-preview")

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(previewArgs)
    options.release = 25
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(previewArgs)
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("-enable-preview", true)
    (options as StandardJavadocDocletOptions).addStringOption("source", "25")
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
