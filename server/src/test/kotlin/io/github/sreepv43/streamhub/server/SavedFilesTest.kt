package io.github.sreepv43.streamhub.server

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The server's lists (jobs.json, library.json, config.json) survive overlapping saves and damage. */
class SavedFilesTest {
    private val dir = Files.createTempDirectory("saved").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun overlappingSavesNeverLeaveABrokenFile() {
        val file = File(dir, "jobs.json")
        val pool = Executors.newFixedThreadPool(8)
        repeat(400) { i ->
            pool.execute { writeAtomically(file, ("[" + (0..i % 50).joinToString(",") { "\"job-$it\"" } + "]").toByteArray()) }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        val text = file.readText()
        assertTrue(text, text.startsWith("[\"job-0\"") && text.endsWith("]"))
        assertTrue("no temporary files left", dir.listFiles()!!.none { it.name.contains(".tmp-") })
    }

    @Test
    fun aDamagedFileFallsBackToTheLastGoodOneAndIsKept() {
        val file = File(dir, "jobs.json")
        writeAtomically(file, "[1,2,3]".toByteArray())
        writeAtomically(file, "[1,2,3,4]".toByteArray())
        file.writeText("[1,2,") // damaged, e.g. the computer lost power while saving
        val read = readSaved(file) { text -> ServerJson.decodeFromString(ListSerializer(Int.serializer()), text) }
        assertEquals(listOf(1, 2, 3), read)
        assertTrue("the damaged file is kept", dir.listFiles()!!.any { it.name.startsWith("jobs.json.broken-") })
        // The next save doesn't turn the damaged file into the backup.
        writeAtomically(file, "[5]".toByteArray())
        assertEquals("[1,2,3]", File(dir, "jobs.json.bak").readText())
    }
}
