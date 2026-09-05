// Deliberately a SEPARATE Gradle build from the library. It exists to consume
// the published aar the way a real app would - by coordinate, out of a
// repository, through the Android Gradle Plugin - which it cannot do from
// inside the build that produces it. It is also why the main build needs no
// Android SDK.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
rootProject.name = "android-verify"
