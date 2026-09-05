plugins {
    // AGP 8 with an explicit Kotlin 2.4.10, which is what a consumer of this
    // library actually has to be on. AGP 9's built-in Kotlin is 2.2, and 2.2
    // cannot read 2.4 metadata - see README, "Kotlin version".
    //
    // AGP 8 also uses a Gradle internal API removed in 9.6, which is why this
    // project pins its own Gradle through the wrapper instead of using
    // whatever the library build uses.
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.4.10"
}

android {
    namespace = "io.github.thecryptodonkey.lnurlcash.verify"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.thecryptodonkey.lnurlcash.verify"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// kotlinOptions was removed in Kotlin 2.4; this is the replacement DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The whole point: one coordinate, resolved as a real consumer would.
    implementation("io.github.thecryptodonkey:lnurlcash-kotlin-android:0.1.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
