// Foojay toolchain resolver lets Gradle auto-download a JDK 21 toolchain if one
// is not already installed locally (you still need *some* JDK to start Gradle).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "orbital"
