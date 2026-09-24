package io.github.sreepv43.streamhub.addon

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonUrlsTest {

    @Test
    fun normalizesStremioScheme() {
        assertEquals(
            "https://v3-cinemeta.strem.io/manifest.json",
            AddonUrls.normalizeManifestUrl("stremio://v3-cinemeta.strem.io/manifest.json"),
        )
    }

    @Test
    fun addsSchemeAndManifestPath() {
        assertEquals("https://example.com/addon/manifest.json", AddonUrls.normalizeManifestUrl(" example.com/addon/ "))
        assertEquals("http://192.168.1.2:7000/manifest.json", AddonUrls.normalizeManifestUrl("http://192.168.1.2:7000"))
    }

    @Test
    fun keepsConfigurationInPath() {
        val url = "https://addon.example/%7B%22key%22%3A1%7D/manifest.json"
        assertEquals(url, AddonUrls.normalizeManifestUrl(url))
        assertEquals("https://addon.example/%7B%22key%22%3A1%7D", AddonUrls.baseUrl(url))
    }

    @Test
    fun buildsResourceUrls() {
        val transport = "https://v3-cinemeta.strem.io/manifest.json"
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top.json",
            AddonUrls.resourceUrl(transport, "catalog", "movie", "top"),
        )
        assertEquals(
            "https://v3-cinemeta.strem.io/stream/series/tt0944947%3A1%3A2.json",
            AddonUrls.resourceUrl(transport, "stream", "series", "tt0944947:1:2"),
        )
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/search=the%20matrix&skip=100.json",
            AddonUrls.resourceUrl(transport, "catalog", "movie", "top", listOf("search" to "the matrix", "skip" to "100")),
        )
    }

    @Test
    fun keepsQueryOfTransportUrl() {
        assertEquals(
            "https://a.example/meta/movie/tt1.json?token=abc",
            AddonUrls.resourceUrl("https://a.example/manifest.json?token=abc", "meta", "movie", "tt1"),
        )
    }

    @Test
    fun encodesLikeJavascript() {
        assertEquals("a%20b!'()*~-_.", AddonUrls.encodeComponent("a b!'()*~-_."))
        assertEquals("%26%3D%2F%3F%23", AddonUrls.encodeComponent("&=/?#"))
    }
}
