package io.github.sreepv43.streamhub.server

import java.io.File
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * streamhub-server [--data=FOLDER] [--port=PORT] [--add-storage=LABEL=PATH]
 *
 * Data folder: --data, else $STREAMHUB_DATA, else ~/.streamhub-server.
 */
fun main(args: Array<String>) {
    fun option(name: String): String? = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
    val dataDir = File(option("data") ?: System.getenv("STREAMHUB_DATA") ?: File(System.getProperty("user.home"), ".streamhub-server").path)
    dataDir.mkdirs()
    try {
        Natives.load(dataDir)
    } catch (e: Exception) {
        System.err.println(e.message)
        exitProcess(1)
    }
    val server = StreamHubServer(dataDir, option("port")?.toIntOrNull())
    option("add-storage")?.let { spec ->
        val label = spec.substringBefore('=')
        val path = spec.substringAfter('=')
        val added = server.addStorage(label, path)
        println("Added storage \"${added.label}\" at ${added.path}")
    }
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}

/** Finds the native torrent engine for this computer in the distribution's natives/ folder. */
object Natives {
    fun load(dataDir: File) {
        if (System.getProperty("libtorrent4j.jni.path") != null) return
        val os = System.getProperty("os.name").lowercase()
        val (platform, extension) = when {
            "win" in os -> "windows" to "dll"
            "mac" in os || "darwin" in os -> "macos" to "dylib"
            else -> "linux" to "so"
        }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "arm64"
            "arm", "armv7l", "armv7" -> "arm"
            "x86", "i386", "i686" -> "x86"
            else -> System.getProperty("os.arch").lowercase()
        }
        val home = runCatching { File(Natives::class.java.protectionDomain.codeSource.location.toURI()).parentFile.parentFile }.getOrNull()
        val jars = listOfNotNull(home?.let { File(it, "natives") }, File("natives"))
            .flatMap { dir -> dir.listFiles()?.filter { it.name.startsWith("libtorrent4j-$platform") && it.extension == "jar" }.orEmpty() }
        for (jar in jars) {
            ZipFile(jar).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { e ->
                    !e.isDirectory && e.name.endsWith(".$extension") && e.name.split('/').any { it == arch }
                } ?: return@use
                val out = File(dataDir, "native/$arch/" + entry.name.substringAfterLast('/'))
                if (!out.isFile || out.length() != entry.size) {
                    out.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                System.setProperty("libtorrent4j.jni.path", out.absolutePath)
                return
            }
        }
        throw IllegalStateException(
            "StreamHub Server has no torrent engine for $os on $arch. Supported: Windows (64-bit), " +
                "Linux (x86-64, 32-bit ARM) and macOS.",
        )
    }
}
