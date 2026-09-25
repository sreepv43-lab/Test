package io.github.sreepv43.streamhub.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNamesTest {

    @Test
    fun prefersAddonFilename() {
        assertEquals(
            "Movie.2020.1080p.mkv",
            FileNames.choose("Movie.2020.1080p.mkv", "attachment; filename=\"x.mp4\"", "https://a/b.avi", "Movie", null),
        )
    }

    @Test
    fun usesContentDispositionThenUrlThenTitle() {
        assertEquals("x y.mp4", FileNames.choose(null, "attachment; filename*=UTF-8''x%20y.mp4", "https://a/b", "T", null))
        assertEquals("My Movie.mkv", FileNames.choose(null, null, "https://a/dl/My%20Movie.mkv?token=1", "T", null))
        assertEquals("Title S1E2.mp4", FileNames.choose(null, null, "https://a/stream", "Title S1E2", "video/mp4"))
        assertEquals("Title.mkv", FileNames.choose(null, null, "http://srv:11470/abc/1", "Title", "application/octet-stream"))
    }

    @Test
    fun sanitizesIllegalCharacters() {
        assertEquals("a_b_c_.mkv", FileNames.sanitize("a/b:c?.mkv"))
        assertEquals("video", FileNames.sanitize("..."))
    }

    @Test
    fun parsesContentRange() {
        assertEquals(1000L, FileNames.totalFromContentRange("bytes 100-999/1000"))
        assertNull(FileNames.totalFromContentRange("bytes 100-999/*"))
        assertNull(FileNames.totalFromContentRange(null))
    }
}
