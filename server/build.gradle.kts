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
// JavaFX for the installers' own window (AppWindow.kt); not in the zip, which opens the browser.
val javafxVersion = "21.0.5"
val javafxModules = listOf("base", "graphics", "controls", "media", "web")
val javafxLinux: Configuration by configurations.creating { isTransitive = false }
val javafxWindows: Configuration by configurations.creating { isTransitive = false }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // -PlibtorrentJar=/path/libtorrent4j.jar builds without downloading it (offline checks).
    val localJar = findProperty("libtorrentJar") as String?
    if (localJar != null) implementation(files(localJar)) else implementation("org.libtorrent4j:libtorrent4j:$libtorrent")
    natives("org.libtorrent4j:libtorrent4j-linux:$libtorrent")
    natives("org.libtorrent4j:libtorrent4j-windows:$libtorrent")
    natives("org.libtorrent4j:libtorrent4j-macos:$libtorrent")
    testNative("org.libtorrent4j:libtorrent4j-linux:$libtorrent")
    // -PjavafxDir=/folder/with/javafx-*-linux.jars compiles without downloading them (offline checks).
    val localFx = findProperty("javafxDir") as String?
    javafxModules.forEach { module ->
        if (localFx != null) compileOnly(files("$localFx/javafx-$module-$javafxVersion-linux.jar"))
        else compileOnly("org.openjfx:javafx-$module:$javafxVersion:linux")
        javafxLinux("org.openjfx:javafx-$module:$javafxVersion:linux")
        javafxWindows("org.openjfx:javafx-$module:$javafxVersion:win")
    }
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

// Installers with their own Java inside (built with the JDK's jpackage on the matching system):
// Linux .deb (needs fakeroot) and Windows .exe (needs WiX 3). Started from the menu, the program
// opens the control page (starting the server if needed).
val jpackage = File(System.getProperty("java.home"), "bin/jpackage" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "").path
// What the server and its window (JavaFX) use. The .deb lists its system libraries itself
// (packaging/control) so it installs on older and newer distros alike.
val installerModules = "java.base,java.logging,java.net.http,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,java.management,java.naming," +
    "java.desktop,java.xml,jdk.jsobject,jdk.xml.dom,jdk.jfr"
val installerDescription = "StreamHub Server: downloads for the StreamHub TV app, even with the TV off"

fun installerInput(name: String, platform: String, javafx: Configuration) = tasks.register<Sync>(name) {
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    from(javafx)
    from({ natives.filter { platform in it.name } }) { into("natives") }
    into(layout.buildDirectory.dir("installer/input-$platform"))
}

val linuxInstallerInput = installerInput("linuxInstallerInput", "linux", javafxLinux)
val windowsInstallerInput = installerInput("windowsInstallerInput", "windows", javafxWindows)

fun launcherOptions(platform: String, icon: String) = listOf(
    "--input", layout.buildDirectory.dir("installer/input-$platform").get().asFile.path,
    "--main-jar", tasks.jar.get().archiveFileName.get(),
    "--main-class", application.mainClass.get(),
    "--arguments", "--open-browser",
    "--java-options", "-Xmx768m",
    "--add-modules", installerModules,
    "--icon", file(icon).path,
)

val linuxInstaller by tasks.registering(Exec::class) {
    dependsOn(linuxInstallerInput)
    val out = layout.buildDirectory.dir("installer/out").get().asFile
    doFirst { out.deleteRecursively() }
    commandLine(
        listOf(
            jpackage, "--type", "deb",
            "--name", "streamhub-server",
            "--app-version", project.version.toString(),
            "--description", installerDescription,
            "--vendor", "StreamHub",
        ) + launcherOptions("linux", "packaging/streamhub-server.png") + listOf(
            "--resource-dir", file("packaging").path,
            "--linux-shortcut",
            "--linux-menu-group", "AudioVideo;Network;",
            "--linux-app-category", "video",
            "--dest", out.path,
        ),
    )
}

// The Windows program on its own (CI starts it once as a check before packing it into the .exe).
val windowsAppImage by tasks.registering(Exec::class) {
    dependsOn(windowsInstallerInput)
    val out = layout.buildDirectory.dir("installer/windows-image").get().asFile
    doFirst { out.deleteRecursively() }
    commandLine(
        listOf(
            jpackage, "--type", "app-image",
            "--name", "StreamHub Server",
            "--app-version", project.version.toString(),
            "--description", installerDescription,
            "--vendor", "StreamHub",
        ) + launcherOptions("windows", "packaging/streamhub-server.ico") + listOf("--dest", out.path),
    )
}

val windowsInstaller by tasks.registering(Exec::class) {
    dependsOn(windowsAppImage)
    val image = layout.buildDirectory.dir("installer/windows-image/StreamHub Server").get().asFile
    val out = layout.buildDirectory.dir("installer/out").get().asFile
    doFirst { out.deleteRecursively() }
    commandLine(
        jpackage, "--type", "exe",
        "--app-image", image.path,
        "--name", "StreamHub Server",
        "--app-version", project.version.toString(),
        "--description", installerDescription,
        "--vendor", "StreamHub",
        // Installs for the current user (no administrator needed) into %LOCALAPPDATA%; a newer
        // installer replaces the old version (same upgrade id).
        "--win-per-user-install",
        "--win-upgrade-uuid", "6f1d6a3e-2b8c-4c55-9a3e-5e1b7c2d9f40",
        "--win-menu", "--win-menu-group", "StreamHub",
        "--win-shortcut",
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
