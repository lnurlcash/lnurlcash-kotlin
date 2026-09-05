import java.util.zip.ZipFile

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
    "win32-aarch64" to "lnurlcash_core.dll",
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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Central requires a javadoc artifact. Running Dokka over machine-written
// bindings would produce a hundred pages describing FfiConverterUInt64, so
// this one page says what the artifact is and points at the real docs instead.
val javadocIndex = tasks.register("javadocIndex") {
    val out = layout.buildDirectory.file("javadoc-stub/index.html")
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            <!doctype html>
            <title>lnurlcash-kotlin-bindings</title>
            <h1>lnurlcash-kotlin-bindings</h1>
            <p>Generated UniFFI bindings and the packaged native core behind
            <a href="https://github.com/TheCryptoDonkey/lnurlcash-kotlin">lnurlcash-kotlin</a>.
            This artifact is machine written and arrives as a transitive
            dependency; depend on <code>lnurlcash-kotlin</code> instead, and
            read its API documentation.</p>
            """.trimIndent()
        )
    }
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(javadocIndex)
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
val verifyNativeLibraries = tasks.register("verifyNativeLibraries") {
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

// verifyNativeLibraries checks a directory. This checks the artifact, which is
// a different claim: a file can sit in natives/ and still not be packaged, and
// the jar is the only thing a consumer ever sees. It also catches a stand-in -
// a real core is megabytes, so anything under a kilobyte is a placeholder
// someone left behind.
val verifyPackagedNatives = tasks.register("verifyPackagedNatives") {
    dependsOn(tasks.jar)
    val archive = tasks.jar.flatMap { it.archiveFile }
    val expected = nativeLibraries
    doLast {
        ZipFile(archive.get().asFile).use { jar ->
            val wrong = expected.mapNotNull { (prefix, library) ->
                val entry = jar.getEntry("$prefix/$library")
                when {
                    entry == null -> "$prefix/$library is not in the jar"
                    entry.size < 1024 -> "$prefix/$library is only ${entry.size} bytes"
                    else -> null
                }
            }
            check(wrong.isEmpty()) {
                "the jar would not work everywhere it claims to:\n" +
                    wrong.joinToString("\n") { "  $it" }
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyNativeLibraries, verifyPackagedNatives)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)
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
