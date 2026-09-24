package io.github.sreepv43.streamhub.torrent

import io.github.sreepv43.streamhub.addon.AddonUrls
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Tiny HTTP server on 127.0.0.1 that exposes files inside torrents as normal seekable HTTP
 * resources (`GET /stream?src=…&file=…` with Range support). The video player and the downloader
 * read from it exactly like from any other web server.
 */
class TorrentHttpServer(private val engine: TorrentEngine) {
    private val log = Logger.getLogger("TorrentHttpServer")
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "torrent-http").apply { isDaemon = true } }

    /** Starts the server if needed and returns its base URL, e.g. http://127.0.0.1:43123. */
    @Synchronized
    fun baseUrl(): String {
        val socket = server?.takeUnless { it.isClosed } ?: ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also {
            server = it
            Thread({ acceptLoop(it) }, "torrent-http-accept").apply { isDaemon = true }.start()
        }
        return "http://127.0.0.1:${socket.localPort}"
    }

    fun urlFor(source: String, fileIdx: Int): String = baseUrl() + TorrentLinks.streamPath(source, fileIdx)

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            try {
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return respond(out, 400, "Bad Request")
                val method = parts[0]
                val target = parts[1]
                if (method != "GET" && method != "HEAD") return respond(out, 405, "Method Not Allowed")
                if (target.substringBefore('?') != "/stream") return respond(out, 404, "Not Found")
                val (source, fileIdx) = TorrentLinks.parseQuery(target.substringAfter('?', ""))
                    ?: return respond(out, 400, "Missing src")

                val file = try {
                    engine.open(source, fileIdx)
                } catch (e: IOException) {
                    return respond(out, 504, e.message ?: "Torrent unavailable")
                }
                file.use { serve(it, method == "HEAD", headers["range"], out) }
            } catch (e: SocketException) {
                // Client went away (seek or stop): normal.
            } catch (e: Exception) {
                log.log(Level.WARNING, "torrent http request failed", e)
            }
        }
    }

    private fun serve(file: TorrentEngine.TorrentFile, headOnly: Boolean, rangeHeader: String?, out: OutputStream) {
        val range = ByteRange.parse(rangeHeader, file.size)
        if (range != null && (range.start >= file.size || range.start > range.endInclusive)) {
            writeHead(out, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */${file.size}", "Content-Length" to "0"))
            out.flush()
            return
        }
        val start = range?.start ?: 0L
        val end = range?.endInclusive ?: (file.size - 1)
        val headers = mutableListOf(
            "Content-Type" to mimeFor(file.name),
            "Accept-Ranges" to "bytes",
            "Content-Length" to (end - start + 1).toString(),
            "Content-Disposition" to "inline; filename*=UTF-8''" + AddonUrls.encodeComponent(file.name),
            "Cache-Control" to "no-store",
        )
        if (range != null) headers += "Content-Range" to "bytes $start-$end/${file.size}"
        writeHead(out, if (range != null) 206 else 200, if (range != null) "Partial Content" else "OK", headers)
        if (headOnly) {
            out.flush()
            return
        }
        val buffer = ByteArray(256 * 1024)
        var position = start
        var waitedMs = 0L
        while (position <= end) {
            val want = minOf(buffer.size.toLong(), end - position + 1).toInt()
            val read = file.read(position, buffer, 0, want, timeoutMs = 1_000)
            if (read < 0) break
            if (read == 0) {
                // Piece not there yet: keep the connection alive while peers deliver it.
                out.flush()
                waitedMs += 1_000
                if (waitedMs > PIECE_WAIT_LIMIT_MS) throw IOException("Timed out waiting for torrent data")
                continue
            }
            waitedMs = 0
            out.write(buffer, 0, read)
            position += read
        }
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, message: String) {
        val body = message.toByteArray()
        writeHead(out, code, message, listOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to body.size.toString()))
        out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $code ${reason.replace(Regex("[\\r\\n]"), " ")}\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 16_384) throw IOException("Header line too long")
            sb.append(c.toChar())
        }
    }

    companion object {
        private const val PIECE_WAIT_LIMIT_MS = 5 * 60_000L

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> "application/octet-stream"
        }
    }
}
