plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    api("net.java.dev.jna:jna:5.15.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
