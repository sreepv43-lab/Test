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
    val windows = "win" in os

    /** The installed program (set by the installer's launcher); null when run from the zip. */
    private val launcher: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).canExecute() }

    private val autostartFile: File
        get() = File(System.getenv("XDG_CONFIG_HOME") ?: File(System.getProperty("user.home"), ".config").path, "autostart/streamhub-server.desktop")

    /** Windows: a shortcut in the user's Startup folder. */
    private val startupShortcut: File
        get() = File(System.getenv("APPDATA").orEmpty(), "Microsoft\\Windows\\Start Menu\\Programs\\Startup\\StreamHub Server.lnk")

    /** Whether the server starts when the user logs in; null when the page can't switch it here. */
    fun autostart(): Boolean? = when {
        launcher == null -> null
        linux -> autostartFile.isFile
        windows -> startupShortcut.isFile
        else -> null
    }

    fun setAutostart(enabled: Boolean) {
        val program = checkNotNull(launcher) { "Starting with the computer can be switched only in the installed server" }
        if (windows) {
            if (enabled) windowsShortcut(startupShortcut, program, "--background") else startupShortcut.delete()
            return
        }
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

    /** Makes a Windows shortcut with PowerShell (the script goes encoded, so paths need no quoting). */
    private fun windowsShortcut(shortcut: File, target: String, arguments: String) {
        fun quoted(value: String) = "'" + value.replace("'", "''") + "'"
        shortcut.parentFile.mkdirs()
        val script = "\$s = (New-Object -ComObject WScript.Shell).CreateShortcut(${quoted(shortcut.path)}); " +
            "\$s.TargetPath = ${quoted(target)}; \$s.Arguments = ${quoted(arguments)}; " +
            "\$s.WorkingDirectory = ${quoted(File(target).parent)}; \$s.Save()"
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        // PowerShell waits for more input while its input is open.
        process.outputStream.close()
        process.inputStream.readBytes()
        check(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0 && shortcut.isFile) {
            "Windows didn't create the startup shortcut"
        }
    }

    /** Whether this computer has a screen to show a window on. */
    fun hasDisplay(): Boolean =
        !linux || !System.getenv("DISPLAY").isNullOrEmpty() || !System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()

    fun openBrowser(url: String) {
        val command = when {
            linux -> listOf("xdg-open", url)
            "mac" in os -> listOf("open", url)
            "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> null
        }
        if (command != null) runCatching { ProcessBuilder(command).inheritIO().start() }
    }

    /** The version of the StreamHub Server already answering on [port] of this computer, if any. */
    fun runningVersion(port: Int): String? = runCatching {
        val connection = URI("http://127.0.0.1:$port/api/hello").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1_500
        connection.readTimeout = 1_500
        val hello = ServerJson.decodeFromString(Hello.serializer(), connection.inputStream.use { it.readBytes().decodeToString() })
        hello.version.takeIf { hello.app == "streamhub-server" }
    }.getOrNull()

    fun alreadyRunning(port: Int): Boolean = runningVersion(port) != null

    /** Asks the server on [port] to quit (an older version after an update) and waits until it has. */
    fun stopRunning(port: Int): Boolean {
        runCatching {
            val connection = URI("http://127.0.0.1:$port/admin/api/stop").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-StreamHub", "admin")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write("{}".toByteArray()) }
            connection.inputStream.use { it.readBytes() }
        }
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            if (!alreadyRunning(port)) {
                Thread.sleep(500)
                return true
            }
            Thread.sleep(300)
        }
        return false
    }

    /** This computer's addresses on the home network (what the TV needs), e.g. "192.168.1.20". */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { nic ->
                nic.isUp && !nic.isLoopback && !nic.isVirtual &&
                    VIRTUAL.none { nic.name.startsWith(it) } &&
                    VIRTUAL_ADAPTERS.none { nic.displayName.orEmpty().contains(it, ignoreCase = true) }
            }
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

    /** Windows names its adapters only in the display name (WSL / Hyper-V, VPNs, virtual machines). */
    private val VIRTUAL_ADAPTERS = listOf("Hyper-V", "vEthernet", "VirtualBox", "VMware", "WSL", "TAP-", "Wintun", "WireGuard", "Tailscale", "ZeroTier")

    private val VIRTUAL = listOf("docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "tun", "tap", "zt", "tailscale")
}
