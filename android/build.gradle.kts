plugins {
    base
    `maven-publish`
    signing
}

group = "io.github.thecryptodonkey"
version = rootProject.version

// Android 5.0. The NDK builds against it, the manifest declares it, and the
// two must agree or a consumer's merge fails with a minSdk conflict.
val minSdk = 21

// ABI directory names are Android's, not JNA's. On Android the core is not
// extracted from the classpath at all: AGP merges jni/<abi>/ into the APK's
// lib/<abi>/, and JNA falls through to System.loadLibrary, which finds it in
// the app's native library directory.
val androidAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

val androidNativesDir = rootProject.layout.projectDirectory.dir("android-natives")

val androidManifest = tasks.register("androidManifest") {
    val out = layout.buildDirectory.file("aar/AndroidManifest.xml")
    outputs.file(out)
    val sdk = minSdk
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="io.github.thecryptodonkey.lnurlcash.android">
                <uses-sdk android:minSdkVersion="$sdk" />
            </manifest>
            """.trimIndent() + "\n"
        )
    }
}

// AGP 7 and later refuse an aar without this file, and the message it gives is
// about the artifact being corrupt rather than about a missing descriptor.
val aarMetadata = tasks.register("aarMetadata") {
    val out = layout.buildDirectory.file("aar/aar-metadata.properties")
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            aarFormatVersion=1
            aarMetadataVersion=1
            minCompileSdk=21
            minCompileSdkExtension=0
            minAndroidGradlePluginVersion=7.0.0
            """.trimIndent() + "\n"
        )
    }
}

// Empty, but its absence makes some AGP versions fail resource merging.
val aarRTxt = tasks.register("aarRTxt") {
    val out = layout.buildDirectory.file("aar/R.txt")
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText("") }
}

// The same Kotlin as the jar, minus the desktop natives. Built from the two
// jars rather than from class directories so that whatever is published for
// the JVM and whatever is published for Android are provably the same classes.
val classesJar = tasks.register<Jar>("classesJar") {
    archiveFileName.set("classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("aar"))
    from(zipTree(rootProject.tasks.named<Jar>("jar").flatMap { it.archiveFile }))
    from(zipTree(project(":lnurlcash-kotlin-bindings").tasks.named<Jar>("jar").flatMap { it.archiveFile })) {
        exclude("linux-*/**", "darwin-*/**", "win32-*/**")
    }
    exclude("META-INF/MANIFEST.MF")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val aar = tasks.register<Zip>("aar") {
    archiveExtension.set("aar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(androidManifest)
    from(classesJar)
    from(aarRTxt)
    from(aarMetadata) { into("META-INF/com/android/build/gradle") }
    from(androidNativesDir) { into("jni") }
}

tasks.named("assemble") { dependsOn(aar) }

tasks.withType<Zip>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Same reasoning as the jar: an aar missing an ABI is a crash on that device,
// found after a dependency resolved cleanly, and Central is permanent.
val verifyAndroidNatives = tasks.register("verifyAndroidNatives") {
    dependsOn(aar)
    val archive = aar.flatMap { it.archiveFile }
    val abis = androidAbis
    doLast {
        java.util.zip.ZipFile(archive.get().asFile).use { zip ->
            val wrong = abis.mapNotNull { abi ->
                val entry = zip.getEntry("jni/$abi/liblnurlcash_core.so")
                when {
                    entry == null -> "jni/$abi/liblnurlcash_core.so is not in the aar"
                    entry.size < 1024 -> "jni/$abi/liblnurlcash_core.so is only ${entry.size} bytes"
                    else -> null
                }
            }
            check(wrong.isEmpty()) {
                "the aar would crash on a device it claims to support:\n" +
                    wrong.joinToString("\n") { "  $it" } +
                    "\nRun scripts/build-android-core.sh."
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyAndroidNatives)
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(zipTree(rootProject.tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile }))
    from(zipTree(project(":lnurlcash-kotlin-bindings").tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile }))
    exclude("META-INF/MANIFEST.MF")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    from(rootProject.tasks.named("dokkaGeneratePublicationJavadoc"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "lnurlcash-kotlin-android"
            artifact(aar)
            artifact(sourcesJar)
            artifact(javadocJar)
            pom {
                packaging = "aar"
                name.set("lnurlcash-kotlin-android")
                description.set(
                    "LNURLcash (LUD-25) bearer notes for Android, over the audited Rust core"
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
                // Written out by hand because there is no `java` component to
                // read them from. JNA is the one that matters: its plain jar
                // carries no Android jnidispatch, so an Android consumer needs
                // the aar or every call fails at load time.
                withXml {
                    val dependencies = asNode().appendNode("dependencies")
                    fun dependency(group: String, artifact: String, ver: String, type: String? = null) {
                        dependencies.appendNode("dependency").apply {
                            appendNode("groupId", group)
                            appendNode("artifactId", artifact)
                            appendNode("version", ver)
                            if (type != null) appendNode("type", type)
                            appendNode("scope", "compile")
                        }
                    }
                    dependency("net.java.dev.jna", "jna", "5.15.0", "aar")
                    dependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21")
                    dependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0")
                    dependency("com.squareup.okhttp3", "okhttp", "4.12.0")
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
    val signingKey = System.getenv("MAVEN_GPG_PRIVATE_KEY")?.takeIf { it.isNotBlank() }
    val signingPassword = System.getenv("MAVEN_GPG_PASSPHRASE")
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
