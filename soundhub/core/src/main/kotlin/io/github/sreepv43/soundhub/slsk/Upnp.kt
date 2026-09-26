package io.github.sreepv43.soundhub.slsk

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Opens the listening port on the home router with UPnP, so other users can connect to us
 * directly. Without it, only users who are themselves reachable can send search results and files.
 */
object Upnp {
    data class Mapping(val port: Int, val localIp: String, val router: String)

    /** Finds the router and maps TCP [port] to this device. Blocking; throws if no router answers. */
    fun mapPort(port: Int, description: String, discoveryTimeoutMs: Int = 3_000): Mapping {
        val locations = discover(discoveryTimeoutMs)
        if (locations.isEmpty()) throw IOException("No UPnP router answered")
        var lastError: IOException? = null
        for (location in locations) {
            try {
                return mapPortAt(URL(location), port, description)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No router accepted the port mapping")
    }

    /** Maps [port] using the router whose device description is at [location]. */
    fun mapPortAt(location: URL, port: Int, description: String): Mapping {
        val xml = http(location, "GET", null, emptyMap())
        val (serviceType, controlUrl) = findConnectionService(xml, location)
            ?: throw IOException("The router doesn't offer port mapping")
        val localIp = localAddressTowards(location)
        http(
            controlUrl,
            "POST",
            addPortMappingBody(serviceType, port, localIp, description),
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPAction" to "\"$serviceType#AddPortMapping\"",
            ),
        )
        return Mapping(port, localIp, location.host)
    }

    private fun discover(timeoutMs: Int): List<String> {
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
            ).toByteArray()
        val locations = LinkedHashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                parseLocation(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let(locations::add)
            }
        }
        return locations.toList()
    }

    internal fun parseLocation(response: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("location:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.startsWith("http") }

    /** The WANIPConnection / WANPPPConnection service type and its absolute control URL. */
    internal fun findConnectionService(xml: String, location: URL): Pair<String, URL>? {
        val base = Regex("<URLBase>\\s*([^<]+?)\\s*</URLBase>").find(xml)?.groupValues?.get(1)?.let(::URL) ?: location
        return Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }
            .mapNotNull { service ->
                val type = Regex("<serviceType>\\s*([^<]+?)\\s*</serviceType>").find(service)?.groupValues?.get(1)
                val control = Regex("<controlURL>\\s*([^<]+?)\\s*</controlURL>").find(service)?.groupValues?.get(1)
                if (type != null && control != null && (type.contains(":WANIPConnection:") || type.contains(":WANPPPConnection:"))) {
                    type to URL(base, control)
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    internal fun addPortMappingBody(serviceType: String, port: Int, localIp: String, description: String): String =
        """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:AddPortMapping xmlns:u="$serviceType">
<NewRemoteHost></NewRemoteHost>
<NewExternalPort>$port</NewExternalPort>
<NewProtocol>TCP</NewProtocol>
<NewInternalPort>$port</NewInternalPort>
<NewInternalClient>$localIp</NewInternalClient>
<NewEnabled>1</NewEnabled>
<NewPortMappingDescription>${xmlEscape(description)}</NewPortMappingDescription>
<NewLeaseDuration>0</NewLeaseDuration>
</u:AddPortMapping></s:Body></s:Envelope>"""

    /** This device's address on the network the router is on. */
    private fun localAddressTowards(location: URL): String = Socket().use { socket ->
        socket.connect(InetSocketAddress(location.host, if (location.port > 0) location.port else 80), 3_000)
        socket.localAddress.hostAddress
    }

    private fun http(url: URL, method: String, body: String?, headers: Map<String, String>): String {
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val error = Regex("<errorDescription>([^<]*)</errorDescription>").find(text)?.groupValues?.get(1)
                throw IOException("Router said HTTP $code${error?.let { ": $it" } ?: ""}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun xmlEscape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
