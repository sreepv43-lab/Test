package io.github.sreepv43.streamhub.server

import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI

/** The parts of running on someone's computer: the browser, starting with the computer, the network. */
object Desktop {
    private val os = System.getProperty("os.name").lowercase()
    private val linux = "linux" in os

    /** The installed program (set by the installer's launcher); null when run from the zip. */
    private val launcher: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).canExecute() }

    private val autostartFile: File
        get() = File(System.getenv("XDG_CONFIG_HOME") ?: File(System.getProperty("user.home"), ".config").path, "autostart/streamhub-server.desktop")

    /** Whether the server starts when the user logs in; null when the page can't switch it here. */
    fun autostart(): Boolean? = if (linux && launcher != null) autostartFile.isFile else null

    fun setAutostart(enabled: Boolean) {
        val program = checkNotNull(launcher) { "Starting with the computer can be switched only in the installed server" }
        if (!enabled) {
            autostartFile.delete()
            return
        }
        autostartFile.parentFile.mkdirs()
        autostartFile.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=StreamHub Server
            Comment=Downloads for StreamHub, even with the TV off
            Exec="$program" --background
            Terminal=false
            X-GNOME-Autostart-enabled=true
            """.trimIndent() + "\n",
        )
    }

    fun openBrowser(url: String) {
        val command = when {
            linux -> listOf("xdg-open", url)
            "mac" in os -> listOf("open", url)
            "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> null
        }
        if (command != null) runCatching { ProcessBuilder(command).inheritIO().start() }
    }

    /** Whether a StreamHub Server already answers on [port] of this computer. */
    fun alreadyRunning(port: Int): Boolean = runCatching {
        val connection = URI("http://127.0.0.1:$port/api/hello").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1_500
        connection.readTimeout = 1_500
        connection.inputStream.use { it.readBytes().decodeToString() }.contains("streamhub-server")
    }.getOrDefault(false)

    /** This computer's addresses on the home network (what the TV needs), e.g. "192.168.1.20". */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && VIRTUAL.none { prefix -> it.name.startsWith(prefix) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    /** Folders to pick a storage from: inside [path], plus the usual places drives appear. */
    fun folders(path: String?): FolderListing {
        val home = File(System.getProperty("user.home"))
        val dir = path?.let { File(it) }?.takeIf { it.isDirectory } ?: home
        val user = System.getProperty("user.name")
        val driveRoots = listOf(File("/media/$user"), File("/run/media/$user"), File("/media"), File("/mnt"), File("/Volumes"))
        val drives = driveRoots.flatMap { root -> root.listFiles()?.filter { it.isDirectory && it.name != user }.orEmpty() }
        val places = buildList {
            add(FolderEntry("Home", home.path))
            drives.forEach { add(FolderEntry(it.name, it.path)) }
            if ("win" in os) File.listRoots().forEach { add(FolderEntry(it.path, it.path)) }
        }.distinctBy { it.path }
        return FolderListing(
            path = dir.absolutePath,
            parent = dir.absoluteFile.parentFile?.path,
            folders = dir.listFiles()
                ?.filter { it.isDirectory && !it.isHidden && !it.name.startsWith('.') }
                ?.sortedBy { it.name.lowercase() }
                ?.map { FolderEntry(it.name, it.path) }
                .orEmpty(),
            places = places,
        )
    }

    private val VIRTUAL = listOf("docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "tun", "tap", "zt", "tailscale")
}
