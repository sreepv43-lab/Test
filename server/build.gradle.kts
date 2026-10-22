import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "io.github.sreepv43"
version = System.getenv("GITHUB_RUN_NUMBER")?.let { "1.0.$it" } ?: "1.0.0"

repositories {
    mavenCentral()
}

val libtorrent = "2.1.0-39"
// The native torrent engine for every supported server platform; packed into the distribution's
// natives/ folder and the right one is unpacked at start (see Natives.kt).
val natives: Configuration by configurations.creating { isTransitive = false }
val testNative: Configuration by configurations.creating { isTransitive = false }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // -PlibtorrentJar=/path/libtorrent4j.jar builds without downloading it (offline checks).
    val localJar = findProperty("libtorrentJar") as String?
    if (localJar != null) implementation(files(localJar)) else implementation("org.libtorrent4j:libtorrent4j:$libtorrent")
    natives("org.libtorrent4j:libtorrent4j-linux:$libtorrent")
    natives("org.libtorrent4j:libtorrent4j-windows:$libtorrent")
    natives("org.libtorrent4j:libtorrent4j-macos:$libtorrent")
    testNative("org.libtorrent4j:libtorrent4j-linux:$libtorrent")
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

application {
    mainClass.set("io.github.sreepv43.streamhub.server.MainKt")
    applicationName = "streamhub-server"
}

distributions {
    main {
        distributionBaseName.set("streamhub-server")
        contents {
            from(natives) { into("natives") }
            from("README.md", "Dockerfile", "streamhub-server.service")
        }
    }
}

// Tests run the real torrent engine (desktop Linux x86-64 build); -PlibtorrentNative=/path/libtorrent4j.so
// uses an already unpacked one.
val unpackTestNative by tasks.registering(Copy::class) {
    from({ testNative.map { zipTree(it) } })
    into(layout.buildDirectory.dir("test-native"))
}

tasks.test {
    val local = findProperty("libtorrentNative") as String?
    if (local == null) dependsOn(unpackTestNative)
    val native = local ?: layout.buildDirectory.file("test-native/lib/x86_64/libtorrent4j.so").get().asFile.absolutePath
    systemProperty("libtorrent4j.jni.path", native)
    // libtorrent installs signal handlers; HotSpot needs signal chaining to coexist with them.
    val jsig = File(System.getProperty("java.home"), "lib/libjsig.so")
    if (jsig.exists()) environment("LD_PRELOAD", jsig.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
