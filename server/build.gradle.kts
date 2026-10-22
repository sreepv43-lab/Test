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

// A Linux installer (.deb) with its own Java inside: double-click to install, then "StreamHub Server"
// in the app menu opens the control page. Built with the JDK's jpackage (needs fakeroot).
val installerInput by tasks.registering(Sync::class) {
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    from({ natives.filter { "linux" in it.name } }) { into("natives") }
    into(layout.buildDirectory.dir("installer/input"))
}

val linuxInstaller by tasks.registering(Exec::class) {
    dependsOn(installerInput)
    val input = layout.buildDirectory.dir("installer/input").get().asFile
    val out = layout.buildDirectory.dir("installer/out").get().asFile
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage").path
    doFirst { out.deleteRecursively() }
    commandLine(
        jpackage, "--type", "deb",
        "--name", "streamhub-server",
        "--app-version", project.version.toString(),
        "--description", "StreamHub Server: downloads for the StreamHub TV app, even with the TV off",
        "--vendor", "StreamHub",
        "--input", input.path,
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", application.mainClass.get(),
        // Started from the menu: show the control page (starting the server if needed).
        "--arguments", "--open-browser",
        "--java-options", "-Xmx768m",
        // Only what the server uses (no desktop graphics libraries, so it installs on any recent distro).
        "--add-modules", "java.base,java.logging,java.net.http,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,java.management,java.naming",
        "--icon", file("packaging/streamhub-server.png").path,
        "--resource-dir", file("packaging").path,
        "--linux-shortcut",
        "--linux-menu-group", "AudioVideo;Network;",
        "--linux-app-category", "video",
        "--dest", out.path,
    )
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
