plugins {
    `java-library`
    alias(libs.plugins.mavenPublish)
}

// group and version come from gradle.properties; the release workflow overrides
// version from the git tag with -Pversion=X.Y.Z.

java {
    toolchain {
        // Default JDK for dev machines; CI overrides with -PtoolchainJdk=21|25 to
        // prove the suite passes on the actual floor JDK, not just via --release.
        val toolchainJdk = providers.gradleProperty("toolchainJdk").map(String::toInt).getOrElse(25)
        languageVersion = JavaLanguageVersion.of(toolchainJdk)
    }
    // sourcesJar/javadocJar are configured by the maven-publish plugin below.
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

mavenPublishing {
    // Uploads to the Central Portal and releases the validated deployment in one task
    // (publishToMavenCentral). Credentials and the signing key arrive as
    // ORG_GRADLE_PROJECT_* environment variables in CI — see RELEASING.md.
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(group.toString(), "fforj", version.toString())

    pom {
        name.set("fforj")
        description.set(
            "ﬀorj — functional for Java. Result, Validated, NonEmptyList, Retry " +
                    "on Java 21+. Zero runtime dependencies."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/fforj/fforj")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit/")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("tibtof")
                name.set("Tiberiu Tofan")
                url.set("https://github.com/tibtof")
            }
        }

        scm {
            url.set("https://github.com/fforj/fforj")
            connection.set("scm:git:git://github.com/fforj/fforj.git")
            developerConnection.set("scm:git:ssh://git@github.com/fforj/fforj.git")
        }
    }
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
