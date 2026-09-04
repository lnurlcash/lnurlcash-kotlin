plugins {
    kotlin("jvm") version "2.4.10" apply true
    `java-library`
    `maven-publish`
    signing
    // Javadoc-format API docs. Central requires a javadoc artifact to exist;
    // an empty one satisfies that and helps nobody, and the public API here is
    // already KDoc'd because explicitApi() insists on it.
    id("org.jetbrains.dokka-javadoc") version "2.2.0"
}

group = "io.github.thecryptodonkey"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // the generated bindings, which load the Rust core through JNA
    api(project(":lnurlcash-kotlin-bindings"))
    // OkHttp, specifically for retryOnConnectionFailure(false). The JDK's own
    // HttpClient retries idempotent GETs on a mid-flight connection reset and
    // offers no way to switch that off - which is fatal here, because an
    // LNURLcash mutation is a GET that is not idempotent. See LnurlcashClient.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // tests only: reading the shared conformance vectors and the mock mint's
    // test hooks. The library itself never parses JSON - the Rust core does.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    explicitApi()
    compilerOptions {
        // 17 is the floor Android tooling is comfortable with, and emitting it
        // needs no JDK 17 installed - only a compiler new enough to target it
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Not java.withJavadocJar(): that wires the `javadoc` task, which reads Java
// sources, of which there are none here - so it produces an empty jar.
val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationJavadoc"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// A published jar should be a function of its inputs and nothing else, so two
// builds of one tag are byte-identical and a consumer can check that claim.
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    useJUnitPlatform()
    // JNA finds the Rust core here. CI builds it first; locally, run
    // scripts/build-core.sh.
    systemProperty(
        "jna.library.path",
        System.getProperty("jna.library.path")
            ?: file("${rootDir}/../lnurlcash-core/target/release").absolutePath
    )
    System.getenv("LNURLCASH_CONFORMANCE")?.let { environment("LNURLCASH_CONFORMANCE", it) }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)
            pom {
                name.set("lnurlcash-kotlin")
                description.set("LNURLcash (LUD-25) bearer notes for Kotlin and the JVM")
                url.set("https://github.com/TheCryptoDonkey/lnurlcash-kotlin")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("TheCryptoDonkey")
                        name.set("TheCryptoDonkey")
                        url.set("https://github.com/TheCryptoDonkey")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/TheCryptoDonkey/lnurlcash-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/TheCryptoDonkey/lnurlcash-kotlin.git")
                    url.set("https://github.com/TheCryptoDonkey/lnurlcash-kotlin")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/TheCryptoDonkey/lnurlcash-kotlin/issues")
                }
            }
        }
    }
    repositories {
        // Not a remote. Central's Portal API takes one upload of a whole
        // deployment, so the release assembles both modules into a single
        // local repository tree and posts that as one bundle. Nothing is
        // published a module at a time, and a half-uploaded release is not a
        // state that can exist.
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    // Central rejects an unsigned deployment. The key is only present in the
    // release workflow, so an ordinary local `gradle build` neither needs one
    // nor silently publishes something unsigned.
    // Blank, not just null. An Actions `env:` bound to a secret that does not
    // exist is set to the empty string rather than left unset, so a null check
    // alone turns signing ON with an empty key and fails with "Could not read
    // PGP secret key" - which reads like a malformed key rather than a missing
    // one, and makes a dry run impossible before the secrets are set up.
    val signingKey = System.getenv("MAVEN_GPG_PRIVATE_KEY")?.takeIf { it.isNotBlank() }
    val signingPassword = System.getenv("MAVEN_GPG_PASSPHRASE")
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
