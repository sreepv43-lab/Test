package io.github.sreepv43.soundhub.slsk

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class UpnpTest {
    private val description = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
<device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
<serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
<controlURL>/l3f</controlURL></service></serviceList>
<deviceList><device><deviceList><device><serviceList>
<service>
  <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
  <controlURL>/ctl/IPConn</controlURL>
</service>
</serviceList></device></deviceList></device></deviceList>
</device></root>"""

    @Test
    fun readsLocationFromSsdpAnswer() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nLocation: http://192.168.1.1:5000/rootDesc.xml\r\nST: x\r\n\r\n"
        assertEquals("http://192.168.1.1:5000/rootDesc.xml", Upnp.parseLocation(answer))
        assertNull(Upnp.parseLocation("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test
    fun findsTheWanConnectionService() {
        val (type, control) = Upnp.findConnectionService(description, URL("http://192.168.1.1:5000/rootDesc.xml"))!!
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", type)
        assertEquals(URL("http://192.168.1.1:5000/ctl/IPConn"), control)
    }

    @Test
    fun mapsThePortWithSoap() {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val router = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        router.createContext("/rootDesc.xml") { exchange ->
            val bytes = description.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        router.createContext("/ctl/IPConn") { exchange ->
            requests += exchange.requestHeaders.getFirst("SOAPAction") to exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        router.start()
        try {
            val mapping = Upnp.mapPortAt(URL("http://127.0.0.1:${router.address.port}/rootDesc.xml"), 2234, "SoundHub")
            assertEquals(2234, mapping.port)
            assertEquals("127.0.0.1", mapping.localIp)
            val (action, body) = requests.single()
            assertEquals("\"urn:schemas-upnp-org:service:WANIPConnection:1#AddPortMapping\"", action)
            assertTrue(body.contains("<NewExternalPort>2234</NewExternalPort>"))
            assertTrue(body.contains("<NewInternalClient>127.0.0.1</NewInternalClient>"))
        } finally {
            router.stop(0)
        }
    }
}
