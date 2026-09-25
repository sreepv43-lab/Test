package io.github.sreepv43.streamhub.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    private val manifestJson = """
        {
          "id": "com.linvo.cinemeta",
          "version": "3.0.13",
          "name": "Cinemeta",
          "description": "The official addon for movie and series catalogs",
          "types": ["movie", "series"],
          "resources": ["catalog", "meta", "addon_catalog"],
          "idPrefixes": ["tt"],
          "catalogs": [
            {"type": "movie", "id": "top", "genres": ["Action"], "extra": [
              {"name": "genre", "options": ["Action", "Drama"]},
              {"name": "search"},
              {"name": "skip"}
            ], "extraSupported": ["search", "genre", "skip"]},
            {"type": "movie", "id": "year", "extra": [{"name": "genre", "isRequired": true, "options": ["2024"]}]}
          ],
          "behaviorHints": {"newEpisodeNotifications": true}
        }
    """.trimIndent()

    @Test
    fun parsesManifestWithStringResources() {
        val manifest = StremioJson.decodeFromString(Manifest.serializer(), manifestJson)
        assertEquals("Cinemeta", manifest.name)
        assertEquals(listOf("catalog", "meta", "addon_catalog"), manifest.resources.map { it.name })
        assertTrue(manifest.catalogs[0].supportsExtra("search"))
        assertTrue(manifest.catalogs[0].requiredExtras.isEmpty())
        assertEquals(setOf("genre"), manifest.catalogs[1].requiredExtras)
        assertEquals(listOf("Action", "Drama"), manifest.catalogs[0].options("genre"))
    }

    @Test
    fun parsesObjectResourcesAndMatchesIds() {
        val json = """
            {"id":"x","name":"X","types":["movie","series"],"catalogs":[],
             "resources":["catalog",{"name":"stream","types":["movie"],"idPrefixes":["tt","kitsu"]}]}
        """
        val addon = InstalledAddon("https://x/manifest.json", StremioJson.decodeFromString(Manifest.serializer(), json))
        assertTrue(addon.supports("stream", "movie", "tt123"))
        assertTrue(addon.supports("stream", "movie", "kitsu:1"))
        assertFalse(addon.supports("stream", "series", "tt123"))
        assertFalse(addon.supports("stream", "movie", "yt_id:abc"))
        assertFalse(addon.supports("meta", "movie", "tt123"))
    }

    @Test
    fun resourcesWithoutPrefixesAcceptAnyId() {
        val json = """{"id":"x","types":["movie"],"resources":["stream"]}"""
        val addon = InstalledAddon("https://x/manifest.json", StremioJson.decodeFromString(Manifest.serializer(), json))
        assertTrue(addon.supports("stream", "movie", "anything"))
    }

    @Test
    fun parsesSeriesMetaWithNumbersAndNulls() {
        val json = """
            {"meta":{"id":"tt0944947","type":"series","name":"Game of Thrones","releaseInfo":2011,
             "imdbRating":9.2,"poster":null,"genres":["Drama"],"unknownField":{"a":1},
             "videos":[{"id":"tt0944947:1:1","name":"Winter Is Coming","season":1,"episode":1,"released":"2011-04-17T02:00:00.000Z"},
                       {"id":"tt0944947:1:2","title":"The Kingsroad","season":1,"number":2}]}}
        """
        val meta = StremioJson.decodeFromString(MetaResponse.serializer(), json).meta!!
        assertEquals("2011", meta.releaseInfo)
        assertEquals("9.2", meta.imdbRating)
        assertNull(meta.poster)
        assertEquals("Winter Is Coming", meta.videos[0].displayTitle)
        assertEquals(2, meta.videos[1].episodeNumber)
    }

    @Test
    fun parsesStreams() {
        val json = """
            {"streams":[
              {"name":"Addon\n1080p","title":"Movie.2020.1080p.mkv\n💾 2 GB","infoHash":"ABCDEF","fileIdx":1,
               "sources":["tracker:udp://tracker.example:1337/announce","dht:ABCDEF"],
               "behaviorHints":{"bingeGroup":"g","filename":"Movie.2020.1080p.mkv"}},
              {"url":"https://cdn.example/v.mp4","description":"Direct","behaviorHints":{"notWebReady":true,
               "proxyHeaders":{"request":{"User-Agent":"UA"}}}},
              {"ytId":"dQw4w9WgXcQ"},
              {"externalUrl":"https://www.netflix.com/title/1"}
            ]}
        """
        val streams = StremioJson.decodeFromString(StreamsResponse.serializer(), json).streams
        assertEquals(4, streams.size)
        assertEquals("ABCDEF", streams[0].infoHash)
        assertEquals(1, streams[0].fileIdx)
        assertEquals("Direct", streams[1].details)
        assertEquals("UA", streams[1].behaviorHints.proxyHeaders!!.request["User-Agent"])
    }

    @Test
    fun installedAddonsRoundTrip() {
        val manifest = StremioJson.decodeFromString(Manifest.serializer(), manifestJson)
        val list = listOf(InstalledAddon("https://v3-cinemeta.strem.io/manifest.json", manifest))
        val encoded = StremioJson.encodeToString(installedAddonListSerializer, list)
        assertEquals(list, StremioJson.decodeFromString(installedAddonListSerializer, encoded))
    }
}
