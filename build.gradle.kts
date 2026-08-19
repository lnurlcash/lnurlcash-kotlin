plugins {
    kotlin("jvm") version "2.4.10" apply true
    `java-library`
    `maven-publish`
}

group = "io.github.thecryptodonkey"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // the generated bindings, which load the Rust core through JNA
    api(project(":bindings"))
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

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
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
            pom {
                name.set("lnurlcash-kotlin")
                description.set("LNURLcash (LUD-25) bearer notes for Kotlin and the JVM")
                url.set("https://github.com/TheCryptoDonkey/lnurlcash-kotlin")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
