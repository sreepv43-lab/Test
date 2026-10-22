package io.github.sreepv43.streamhub.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNameTest {
    @Test
    fun moviesGiveTitleAndYear() {
        assertEquals(ParsedName("Dune Part Two", 2024, null, null), ReleaseName.parse("Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.mkv"))
        assertEquals(ParsedName("Bethlehem Kudumba Unit", 2025, null, null), ReleaseName.parse("Bethlehem Kudumba Unit (2025) Malayalam 1080p WEB-DL.mkv"))
        assertEquals(ParsedName("Blade Runner 2049", 2017, null, null), ReleaseName.parse("Blade.Runner.2049.2017.1080p.BluRay.x264.mkv"))
        assertEquals(ParsedName("Manjummel Boys", 2024, null, null), ReleaseName.parse("www.Example.org - Manjummel Boys (2024) Malayalam HQ HDRip - 1080p.mkv"))
    }

    @Test
    fun episodesGiveSeasonAndEpisode() {
        assertEquals(ParsedName("Silo", null, 2, 5), ReleaseName.parse("Silo.S02E05.1080p.WEB.h264.mkv"))
        assertEquals(ParsedName("The Office", null, 3, 12), ReleaseName.parse("The Office 3x12.mp4"))
    }

    @Test
    fun titlesCompareWithoutPunctuation() {
        assertEquals(ReleaseName.normalize("Fast & Furious: Hobbs and Shaw"), ReleaseName.normalize("Fast and Furious Hobbs and Shaw"))
    }
}
