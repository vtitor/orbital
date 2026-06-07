import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    // 2.11.0 is the last 2.x release that supports Gradle 8.x (min Gradle 8.13, which the
    // wrapper pins). 2.12.0+ require Gradle 9.0.
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.github.cosmosdbclient"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Target IDE: PyCharm Community 2024.3.x (build 243). Use the marketing version —
    // the plugin downloads the IDE installer from download.jetbrains.com, where files are
    // named by release version, not build number. The plugin is forward compatible (see
    // untilBuild below), so it also installs in newer PyCharm/IDEA.
    intellijPlatform {
        pycharmCommunity("2024.3.5")
        // Platform test fixtures (BasePlatformTestCase, etc.) for headless plugin tests.
        testFramework(TestFrameworkType.Platform)
    }

    // Azure Cosmos DB SQL (Core) API SDK. Declared as `implementation`, so the
    // IntelliJ Platform Gradle Plugin bundles it (and its transitive deps:
    // azure-core, reactor-netty, jackson, ...) into the plugin distribution.
    implementation("com.azure:azure-cosmos:4.80.0")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // The plugin has no custom settings page, so skip building searchable options
    // (avoids launching a headless IDE on every build).
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            // No upper bound: the plugin uses only stable platform APIs, so it stays
            // compatible with future PyCharm/IDEA releases (recommended for 2024.3+).
            untilBuild = provider { null }
        }
    }
}

kotlin {
    // Build/runtime JDK for the IntelliJ Platform 243 line is Java 21.
    jvmToolchain(21)
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
    // Forward Cosmos emulator / account credentials to the forked test JVM so the live
    // integration test can pick them up (it is skipped when they are absent).
    listOf("COSMOS_TEST_ENDPOINT", "COSMOS_TEST_KEY").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    // The emulator uses a self-signed certificate; point the test JVM at a truststore that
    // trusts it (COSMOS_TRUSTSTORE), so the Cosmos SDK can connect over HTTPS.
    System.getenv("COSMOS_TRUSTSTORE")?.takeIf { it.isNotBlank() }?.let { truststore ->
        systemProperty("javax.net.ssl.trustStore", truststore)
        systemProperty("javax.net.ssl.trustStorePassword", System.getenv("COSMOS_TRUSTSTORE_PASSWORD") ?: "changeit")
    }
}
