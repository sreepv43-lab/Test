package io.github.sreepv43.streamhub.torrent

import io.github.sreepv43.streamhub.torrent.CachePolicy.GB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CachePolicyTest {
    private val internal = CacheVolume(CachePolicy.INTERNAL_ID, "Internal storage", File("/data/cache"), false, 32 * GB, 16 * GB)
    private val usb = CacheVolume("1A2B-3C4D", "Seagate 2TB", File("/storage/1A2B-3C4D/cache"), true, 2000 * GB, 1800 * GB, "exFAT")

    @Test
    fun chosenUsbDriveIsUsedWhenConnected() {
        val choice = CachePolicy.choose(CachePolicy.USB, usb.id, usb.label, listOf(internal, usb))
        assertEquals(CacheChoice.Ready(usb, usb.dir!!), choice)
    }

    @Test
    fun chosenUsbDriveNeverFallsBackToInternalStorage() {
        val missing = CachePolicy.choose(CachePolicy.USB, usb.id, usb.label, listOf(internal))
        assertTrue(missing is CacheChoice.Unavailable)
        assertTrue((missing as CacheChoice.Unavailable).message, missing.message.contains("isn't connected"))

        val readOnly = CachePolicy.choose(CachePolicy.USB, usb.id, usb.label, listOf(internal, usb.copy(problem = "mounted read-only")))
        assertTrue(readOnly is CacheChoice.Unavailable && readOnly.message.contains("read-only"))

        val noFolder = CachePolicy.choose(CachePolicy.USB, usb.id, usb.label, listOf(internal, usb.copy(dir = null)))
        assertTrue(noFolder is CacheChoice.Unavailable)

        val noneChosen = CachePolicy.choose(CachePolicy.USB, null, null, listOf(internal, usb))
        assertTrue(noneChosen is CacheChoice.Unavailable)
    }

    @Test
    fun fullDriveKeepsItsReserveAndAsksForCleanup() {
        assertEquals("a 2 TB drive keeps 100 GB free", 100 * GB, CachePolicy.reserveFor(usb))
        assertEquals("a small drive keeps a tenth free", 64 * GB / 10, CachePolicy.reserveFor(usb.copy(totalBytes = 64 * GB)))
        assertEquals(3 * GB, CachePolicy.reserveFor(internal))

        val full = CachePolicy.choose(CachePolicy.USB, usb.id, usb.label, listOf(internal, usb.copy(freeBytes = 99 * GB)))
        assertTrue(full is CacheChoice.Unavailable && full.lowSpace)
        val ok = CachePolicy.choose(CachePolicy.USB, usb.id, usb.label, listOf(internal, usb.copy(freeBytes = 101 * GB)))
        assertTrue(ok is CacheChoice.Ready)
    }

    @Test
    fun internalStorageKeepsThreeGigabytesFree() {
        val low = CachePolicy.choose(CachePolicy.INTERNAL, null, null, listOf(internal.copy(freeBytes = 2 * GB), usb))
        assertTrue("never moves to the drive either", low is CacheChoice.Unavailable && low.lowSpace)
        assertTrue(CachePolicy.choose(CachePolicy.INTERNAL, null, null, listOf(internal, usb)) is CacheChoice.Ready)
    }

    @Test
    fun automaticPrefersADriveThatHoldsLargeFiles() {
        val fat = CacheVolume("AAAA-BBBB", "Stick", File("/storage/AAAA-BBBB/cache"), true, 3000 * GB, 2900 * GB, "FAT32")
        val auto = CachePolicy.choose(CachePolicy.AUTO, null, null, listOf(internal, fat, usb))
        assertEquals(usb.dir, (auto as CacheChoice.Ready).dir)

        // No usable drive: internal storage.
        val noDrive = CachePolicy.choose(CachePolicy.AUTO, null, null, listOf(internal, usb.copy(problem = "read-only")))
        assertEquals(internal.dir, (noDrive as CacheChoice.Ready).dir)
        val fullDrive = CachePolicy.choose(CachePolicy.AUTO, null, null, listOf(internal, usb.copy(freeBytes = 10 * GB)))
        assertEquals(internal.dir, (fullDrive as CacheChoice.Ready).dir)
    }

    @Test
    fun fat32CantHoldFilesOverFourGigabytes() {
        val fat = usb.copy(fileSystem = "FAT32")
        assertNotNull(CachePolicy.fileTooBig(fat, 30 * GB))
        assertNull(CachePolicy.fileTooBig(fat, 3 * GB))
        assertNull("exFAT holds 30 GB files", CachePolicy.fileTooBig(usb, 30 * GB))
        assertNull(CachePolicy.fileTooBig(null, 30 * GB))
    }
}
