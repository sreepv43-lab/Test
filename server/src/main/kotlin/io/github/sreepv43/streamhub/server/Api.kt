package io.github.sreepv43.streamhub.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import kotlinx.serialization.encodeToString

/**
 * The server's HTTP API (JSON) and media streaming. Everything except /api/hello and /api/pair
 * needs a token from pairing ("Authorization: Bearer <token>", or ?token= for players such as VLC).
 * Meant for the home network only: don't forward its port to the internet.
 */
class Api(private val server: StreamHubServer) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            route(exchange)
        } catch (e: IllegalArgumentException) {
            runCatching { reply(exchange, 400, ErrorResponse(e.message ?: "Bad request")) }
        } catch (e: IllegalStateException) {
            runCatching { reply(exchange, 409, ErrorResponse(e.message ?: "Not possible now")) }
        } catch (e: IOException) {
            // The client went away (e.g. the player seeked and closed this request).
        } catch (e: Exception) {
            runCatching { reply(exchange, 500, ErrorResponse(e.message ?: e.javaClass.simpleName)) }
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.rawPath.trimEnd('/')
        val parts = path.split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, Charsets.UTF_8) }
        when {
            path == "/api/hello" -> return reply(exchange, 200, Hello(name = server.config().name, version = server.version))
            path == "/api/pair" && method == "POST" -> {
                val request = ServerJson.decodeFromString(PairRequest.serializer(), body(exchange))
                val result = server.pair(request.code.trim(), request.device)
                return when (result) {
                    is PairResult.Paired -> reply(exchange, 200, PairResponse(result.token, server.config().name))
                    PairResult.WrongCode -> reply(exchange, 403, ErrorResponse("That pairing code isn't right. Look at the code the server shows."))
                    PairResult.TooManyAttempts -> reply(exchange, 429, ErrorResponse("Too many wrong codes. Wait a minute and try again."))
                }
            }
        }
        if (path.isEmpty() || path == "/admin" || path.startsWith("/admin/")) return admin(exchange, method, path)
        if (!server.authorized(token(exchange))) {
            return reply(exchange, 401, ErrorResponse("This device isn't paired with the server. Pair it in StreamHub's settings."))
        }
        when {
            path == "/api/status" -> reply(exchange, 200, server.status())
            path == "/api/downloads" && method == "GET" -> reply(exchange, 200, server.downloads.list())
            path == "/api/downloads" && method == "POST" -> {
                val request = ServerJson.decodeFromString(NewDownload.serializer(), body(exchange))
                reply(exchange, 201, server.downloads.add(request))
            }
            parts.size == 4 && parts[1] == "downloads" && method == "POST" && parts[3] == "pause" ->
                server.downloads.pause(parts[2])?.let { reply(exchange, 200, it) } ?: notFound(exchange)
            parts.size == 4 && parts[1] == "downloads" && method == "POST" && parts[3] == "resume" ->
                server.downloads.resume(parts[2])?.let { reply(exchange, 200, it) } ?: notFound(exchange)
            parts.size == 3 && parts[1] == "downloads" && method == "DELETE" -> {
                val deleteFile = query(exchange)["deleteFile"] == "true"
                if (server.downloads.remove(parts[2], deleteFile)) reply(exchange, 200, ErrorResponse("removed")) else notFound(exchange)
            }
            path == "/api/library" -> reply(exchange, 200, server.library.items())
            path == "/api/library/scan" && method == "POST" -> {
                server.library.requestScan()
                reply(exchange, 202, ErrorResponse("scanning"))
            }
            parts.size == 2 && parts[0] == "media" && (method == "GET" || method == "HEAD") -> media(exchange, parts[1], head = method == "HEAD")
            else -> notFound(exchange)
        }
    }

    /**
     * The control page and its actions. Only for the server computer itself (or, with
     * adminFromNetwork, the home network); its actions need the page's own header, which other
     * websites open in the same browser can't send, and a Host that is an address, not a name
     * (so a website can't reach it by pointing its own name at this computer).
     */
    private fun admin(exchange: HttpExchange, method: String, path: String) {
        val remote = exchange.remoteAddress.address
        val fromHere = remote.isLoopbackAddress ||
            server.config().adminFromNetwork && (remote.isSiteLocalAddress || remote.isLinkLocalAddress)
        val host = exchange.requestHeaders.getFirst("Host").orEmpty().let { h ->
            if (h.startsWith("[")) h.substringAfter('[').substringBefore(']') else h.substringBefore(':')
        }
        val hostOk = host == "localhost" || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || host.contains(':')
        if (!fromHere || !hostOk) {
            return page(exchange, 403, NOT_HERE)
        }
        if (path.isEmpty() || path == "/admin") {
            val html = Api::class.java.getResourceAsStream("/admin.html")!!.use { it.readBytes() }
            return page(exchange, 200, html.decodeToString())
        }
        if (exchange.requestHeaders.getFirst("X-StreamHub") != "admin") return reply(exchange, 403, ErrorResponse("Use the control page"))
        val request = if (method == "POST") ServerJson.decodeFromString(AdminRequest.serializer(), body(exchange).ifEmpty { "{}" }) else AdminRequest()
        fun ok() = reply(exchange, 200, ErrorResponse("ok"))
        fun need(value: String?, what: String): String = requireNotNull(value?.takeIf { it.isNotBlank() }) { "Missing $what" }
        when (path.removePrefix("/admin/api/")) {
            "status" -> reply(exchange, 200, server.adminStatus())
            "downloads" -> reply(exchange, 200, server.downloads.list())
            "folders" -> reply(exchange, 200, Desktop.folders(query(exchange)["path"]))
            "downloads/pause" -> { server.downloads.pause(need(request.id, "id")); ok() }
            "downloads/resume" -> { server.downloads.resume(need(request.id, "id")); ok() }
            "downloads/remove" -> { server.downloads.remove(need(request.id, "id"), request.deleteFile); ok() }
            "storages/add" -> {
                val folder = need(request.path, "folder")
                val label = request.label?.trim()?.ifEmpty { null } ?: java.io.File(folder).name.ifEmpty { folder }
                server.addStorage(label, folder)
                server.library.requestScan()
                ok()
            }
            "storages/remove" -> { server.removeStorage(need(request.id, "id")); ok() }
            "storages/default" -> { server.setDefaultStorage(need(request.id, "id")); ok() }
            "name" -> { server.rename(need(request.name, "name")); ok() }
            "smb" -> { server.setSmbPath(request.path); ok() }
            "devices/remove" -> { server.removeDevice(requireNotNull(request.pairedAt) { "Missing device" }); ok() }
            "autostart" -> { Desktop.setAutostart(request.enabled == true); ok() }
            "library/scan" -> { server.library.requestScan(); ok() }
            "stop" -> {
                ok()
                server.requestStop()
            }
            else -> notFound(exchange)
        }
    }

    private fun page(exchange: HttpExchange, code: Int, html: String) {
        val bytes = html.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("X-Frame-Options", "DENY")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Streams a library file with byte ranges, so players can start anywhere in a 100 GB file. */
    private fun media(exchange: HttpExchange, id: String, head: Boolean) {
        val file = server.library.fileFor(id)
            ?: return reply(exchange, 404, ErrorResponse("This file isn't available on the server now"))
        val length = file.length()
        var start = 0L
        var end = length - 1
        val range = exchange.requestHeaders.getFirst("Range")
        val partial = range != null && range.startsWith("bytes=")
        if (partial) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',').trim()
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-', "").trim()
            if (from.isEmpty()) {
                val suffix = to.toLongOrNull() ?: 0
                start = (length - suffix).coerceAtLeast(0)
            } else {
                start = from.toLongOrNull() ?: 0
                if (to.isNotEmpty()) end = minOf(to.toLongOrNull() ?: end, length - 1)
            }
            if (start >= length || start > end) {
                exchange.responseHeaders.add("Content-Range", "bytes */$length")
                exchange.sendResponseHeaders(416, -1)
                return
            }
        }
        val count = end - start + 1
        val headers = exchange.responseHeaders
        headers.add("Accept-Ranges", "bytes")
        headers.add("Content-Type", contentType(file))
        if (partial) headers.add("Content-Range", "bytes $start-$end/$length")
        if (head) {
            headers.add("Content-Length", count.toString())
            exchange.sendResponseHeaders(if (partial) 206 else 200, -1)
            return
        }
        exchange.sendResponseHeaders(if (partial) 206 else 200, count)
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            val out = exchange.responseBody
            val buffer = ByteArray(BUFFER)
            var left = count
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                left -= read
            }
            out.flush()
        }
    }

    private fun contentType(file: File): String = when (file.extension.lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }

    private fun token(exchange: HttpExchange): String? =
        exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: query(exchange)["token"]

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    private fun body(exchange: HttpExchange): String {
        val bytes = exchange.requestBody.readNBytes(MAX_BODY + 1)
        require(bytes.size <= MAX_BODY) { "Request too large" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun notFound(exchange: HttpExchange) = reply(exchange, 404, ErrorResponse("Not found"))

    private inline fun <reified T> reply(exchange: HttpExchange, code: Int, value: T) {
        val bytes = ServerJson.encodeToString(value).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val BUFFER = 256 * 1024
        private const val MAX_BODY = 64 * 1024
        private const val NOT_HERE = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
            "<body style='font-family:sans-serif;background:#111;color:#eee;padding:40px'><h2>StreamHub Server is running</h2>" +
            "<p>Its control page opens only on the server computer itself: open <b>http://localhost:8095</b> there.</p></body>"
    }
}
