plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    signing
}

group = "io.github.thecryptodonkey"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    api("net.java.dev.jna:jna:5.15.0")
}

// The native core, one build per platform JNA knows how to ask for. The
// directory names are not ours to choose: JNA looks up an unresolved library
// on the classpath at `/<Platform.RESOURCE_PREFIX>/<mapped name>`, and these
// are the prefixes it computes. Get one wrong and the lookup silently falls
// through to the system loader, so the failure is an UnsatisfiedLinkError on a
// consumer's machine rather than anything visible here.
val nativeLibraries = mapOf(
    "linux-x86-64" to "liblnurlcash_core.so",
    "linux-aarch64" to "liblnurlcash_core.so",
    "darwin-x86-64" to "liblnurlcash_core.dylib",
    "darwin-aarch64" to "liblnurlcash_core.dylib",
    "win32-x86-64" to "lnurlcash_core.dll",
)

// Kept OUTSIDE this module's source tree deliberately. CI asserts
// `git diff --exit-code bindings/` to catch a hand edit of generated code, and
// dropping 9MB of freshly built binaries in there would trip that gate on
// every release. It is also outside any build directory, so `clean` cannot
// quietly empty it between the download step and the publish step.
val nativesDir = rootProject.layout.projectDirectory.dir("natives")

// Attached to the jar rather than added as a resource source directory. A
// source set would put 2MB of machine code through processResources on every
// build and, worse, into the SOURCES jar - which is meant to be readable text
// and is what someone reaches for when they want to audit what they are
// running.
tasks.jar {
    from(nativesDir)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Publishing a jar that is missing a platform is worse than not publishing:
// every consumer on that platform gets an UnsatisfiedLinkError at the first
// call, and a Central release cannot be withdrawn. So the check is a hard
// precondition of publishing, and only of publishing - an ordinary local
// build has no natives and does not want any.
val verifyNativeLibraries by tasks.registering {
    val dir = nativesDir
    val expected = nativeLibraries
    doLast {
        val missing = expected
            .filterNot { (prefix, library) -> dir.file("$prefix/$library").asFile.isFile }
            .map { (prefix, library) -> "$prefix/$library" }
        check(missing.isEmpty()) {
            "cannot publish without every native core: missing ${missing.joinToString(", ")} " +
                "under ${dir.asFile}. Run scripts/package-natives.sh, or let the release " +
                "workflow's build matrix produce them."
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyNativeLibraries)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("lnurlcash-kotlin-bindings")
                description.set(
                    "Generated UniFFI bindings and the packaged native core behind " +
                        "lnurlcash-kotlin. Depend on lnurlcash-kotlin, not on this."
                )
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
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val signingKey = System.getenv("MAVEN_GPG_PRIVATE_KEY")
    val signingPassword = System.getenv("MAVEN_GPG_PASSPHRASE")
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
