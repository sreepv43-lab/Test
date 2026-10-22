package io.github.sreepv43.streamhub.server

import java.io.File
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * streamhub-server [--data=FOLDER] [--port=PORT] [--add-storage=LABEL=PATH] [--open-browser] [--background]
 *
 * Data folder: --data, else $STREAMHUB_DATA, else ~/.streamhub-server.
 * --open-browser (what the installed menu entry passes) shows the control page, starting the server
 * first unless it already runs; --background (starting with the computer) just makes sure it runs.
 */
fun main(args: Array<String>) {
    fun option(name: String): String? = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
    val openBrowser = "--open-browser" in args
    val dataDir = File(option("data") ?: System.getenv("STREAMHUB_DATA") ?: File(System.getProperty("user.home"), ".streamhub-server").path)
    dataDir.mkdirs()
    val logFile = File(dataDir, "server.log")
    if (logFile.length() > MAX_LOG_BYTES) logFile.renameTo(File(dataDir, "server.old.log"))
    val log = { line: String ->
        println(line)
        runCatching { logFile.appendText("${java.time.LocalDateTime.now().withNano(0)}  $line\n") }
        Unit
    }
    val port = option("port")?.toIntOrNull() ?: StreamHubServer.configuredPort(dataDir)
    if (Desktop.alreadyRunning(port)) {
        if (openBrowser) Desktop.openBrowser("http://localhost:$port/")
        else if ("--background" !in args) log("StreamHub Server is already running on port $port.")
        return
    }
    try {
        Natives.load(dataDir)
    } catch (e: Exception) {
        log(e.message.orEmpty())
        exitProcess(1)
    }
    val server = StreamHubServer(dataDir, option("port")?.toIntOrNull(), log = log)
    option("add-storage")?.let { spec ->
        val label = spec.substringBefore('=')
        val path = spec.substringAfter('=')
        val added = server.addStorage(label, path)
        log("Added storage \"${added.label}\" at ${added.path}")
    }
    try {
        server.start()
    } catch (e: java.net.BindException) {
        log("Port ${server.port} is used by another program. Choose another with --port, or \"port\" in ${File(dataDir, "config.json")}.")
        exitProcess(1)
    }
    server.onStopRequested = { exitProcess(0) }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    // The installed server starts with the computer from its first start (switch it off on the page).
    val firstRun = File(dataDir, ".started-before")
    if (!firstRun.exists()) {
        if (Desktop.autostart() == false) runCatching { Desktop.setAutostart(true) }.onFailure { log("Couldn't switch on starting with the computer: ${it.message}") }
        runCatching { firstRun.writeText("") }
    }
    if (openBrowser) Desktop.openBrowser("http://localhost:${server.port}/")
    Thread.currentThread().join()
}

private const val MAX_LOG_BYTES = 2L * 1024 * 1024

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
        // natives/ next to lib/ (the zip) or next to the jars (the installed program).
        val jarDir = runCatching { File(Natives::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
        val jars = listOfNotNull(home?.let { File(it, "natives") }, jarDir?.let { File(it, "natives") }, File("natives"))
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
