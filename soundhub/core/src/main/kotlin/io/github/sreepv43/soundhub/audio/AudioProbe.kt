package io.github.sreepv43.soundhub.audio

import java.io.File
import java.io.RandomAccessFile

/** What a file's first bytes say about its audio. [atmos] is null when the header doesn't tell. */
data class ProbeResult(
    val codec: Codec,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val durationSec: Int? = null,
    val atmos: Boolean? = null,
)

/**
 * Reads the real format from the start of a file, so the categories don't depend on file names
 * alone: FLAC/WAV/AIFF/DSF headers, MP4 sample descriptions (including the Dolby Digital Plus
 * `dec3` box that flags Atmos/JOC), Matroska codec IDs, raw E-AC-3 frames and TrueHD major syncs.
 */
object AudioProbe {
    /** How much of the start of a file is worth waiting for before probing. */
    const val HEAD_BYTES = 256 * 1024

    fun probe(head: ByteArray): ProbeResult? = try {
        val start = id3Size(head)
        when {
            ascii(head, start, "fLaC") -> flac(head, start + 4)
            (ascii(head, 0, "RIFF") || ascii(head, 0, "RF64")) && ascii(head, 8, "WAVE") -> wav(head)
            ascii(head, 0, "FORM") && (ascii(head, 8, "AIFF") || ascii(head, 8, "AIFC")) -> aiff(head)
            ascii(head, 0, "DSD ") -> dsf(head)
            ascii(head, 0, "FRM8") -> ProbeResult(Codec.DSD)
            ascii(head, 4, "ftyp") -> mp4(head)
            u32be(head, 0) == 0x1A45DFA3L -> matroska(head)
            ascii(head, 0, "OggS") -> ogg(head)
            u16be(head, start) == AC3_SYNC -> dolbyFrame(head, start)
            indexOf(head, TRUEHD_SYNC, 0, 64) >= 0 -> trueHd(head, indexOf(head, TRUEHD_SYNC, 0, 64))
            isMpegAudioSync(head, start) -> mpegAudio(head, start)
            else -> null
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /**
     * Probes a (possibly still growing) file. MP4 files whose index comes after the audio data are
     * only understood once that index has been downloaded.
     */
    fun probeFile(file: File): ProbeResult? {
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(raf.length(), HEAD_BYTES.toLong()).toInt())
            raf.readFully(head)
            probe(head)?.let { return it }
            if (!ascii(head, 4, "ftyp")) return null
            val length = raf.length()
            val header = ByteArray(16)
            var position = 0L
            while (position + 8 <= length) {
                raf.seek(position)
                raf.readFully(header, 0, 8)
                var size = u32be(header, 0)
                if (size == 1L && position + 16 <= length) {
                    raf.readFully(header, 8, 8)
                    size = (u32be(header, 8) shl 32) or u32be(header, 12)
                } else if (size == 0L) {
                    size = length - position
                }
                if (size < 8) return null
                if (ascii(header, 4, "moov")) {
                    if (size > MAX_MOOV || position + size > length) return null
                    val moov = ByteArray(size.toInt())
                    raf.seek(position)
                    raf.readFully(moov)
                    return try {
                        mp4(moov)
                    } catch (e: IndexOutOfBoundsException) {
                        null
                    }
                }
                position += size
            }
        }
        return null
    }

    // ---- FLAC / PCM containers ----

    /** Parses FLAC metadata blocks starting at [pos] (just after "fLaC"). */
    private fun flac(h: ByteArray, pos: Int): ProbeResult {
        val type = h[pos].toInt() and 0x7F
        if (type != 0) return ProbeResult(Codec.FLAC)
        return streamInfo(h, pos + 4)
    }

    private fun streamInfo(h: ByteArray, info: Int): ProbeResult {
        val p = info + 10
        val rate = ((h[p].u() shl 12) or (h[p + 1].u() shl 4) or (h[p + 2].u() ushr 4))
        val channels = ((h[p + 2].u() ushr 1) and 7) + 1
        val bits = (((h[p + 2].u() and 1) shl 4) or (h[p + 3].u() ushr 4)) + 1
        val samples = ((h[p + 3].u() and 0x0F).toLong() shl 32) or u32be(h, p + 4)
        return ProbeResult(
            Codec.FLAC,
            sampleRate = rate.takeIf { it > 0 },
            bitDepth = bits,
            channels = channels,
            durationSec = if (rate > 0 && samples > 0) (samples / rate).toInt() else null,
        )
    }

    private fun wav(h: ByteArray): ProbeResult {
        var p = 12
        var result = ProbeResult(Codec.WAV)
        var byteRate = 0L
        while (p + 8 <= h.size) {
            val size = u32le(h, p + 4)
            when {
                ascii(h, p, "fmt ") -> {
                    val format = u16le(h, p + 8)
                    val channels = u16le(h, p + 10)
                    val rate = u32le(h, p + 12).toInt()
                    byteRate = u32le(h, p + 16)
                    var bits = u16le(h, p + 22)
                    if (format == WAVE_FORMAT_EXTENSIBLE && size >= 20) bits = u16le(h, p + 26).takeIf { it > 0 } ?: bits
                    result = result.copy(sampleRate = rate, bitDepth = bits, channels = channels)
                }
                ascii(h, p, "data") -> {
                    if (byteRate > 0 && size in 1 until 0xFFFFFFFFL) result = result.copy(durationSec = (size / byteRate).toInt())
                    return result
                }
            }
            p += 8 + size.toInt() + (size.toInt() and 1)
        }
        return result
    }

    private fun aiff(h: ByteArray): ProbeResult {
        var p = 12
        while (p + 8 <= h.size) {
            val size = u32be(h, p + 4).toInt()
            if (ascii(h, p, "COMM")) {
                val channels = u16be(h, p + 8)
                val frames = u32be(h, p + 10)
                val bits = u16be(h, p + 14)
                val rate = extended80(h, p + 16).toInt()
                return ProbeResult(
                    Codec.AIFF,
                    sampleRate = rate,
                    bitDepth = bits,
                    channels = channels,
                    durationSec = if (rate > 0) (frames / rate).toInt() else null,
                )
            }
            p += 8 + size + (size and 1)
        }
        return ProbeResult(Codec.AIFF)
    }

    private fun dsf(h: ByteArray): ProbeResult {
        if (!ascii(h, 28, "fmt ")) return ProbeResult(Codec.DSD)
        val channels = u32le(h, 52).toInt()
        val rate = u32le(h, 56).toInt()
        val samples = u32le(h, 64) or (u32le(h, 68) shl 32)
        return ProbeResult(
            Codec.DSD,
            sampleRate = rate,
            bitDepth = 1,
            channels = channels,
            durationSec = if (rate > 0) (samples / rate).toInt() else null,
        )
    }

    // ---- MP4 / M4A ----

    private class Box(val type: String, val start: Int, val header: Int, val end: Int) {
        val body: Int get() = start + header
    }

    private fun boxes(h: ByteArray, from: Int, to: Int): List<Box> {
        val result = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32be(h, p)
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = (u32be(h, p + 8) shl 32) or u32be(h, p + 12)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header) break
            val end = p + size
            result += Box(String(h, p + 4, 4, Charsets.ISO_8859_1), p, header, minOf(end, to.toLong()).toInt())
            if (end > to) break
            p = end.toInt()
        }
        return result
    }

    private fun mp4(h: ByteArray): ProbeResult? {
        val entry = findSampleEntry(h, 0, h.size) ?: return null
        val type = entry.type
        val version = u16be(h, entry.start + 16)
        val entryChannels = u16be(h, entry.start + 24).takeIf { version < 2 }
        val entryRate = (u32be(h, entry.start + 32) ushr 16).toInt().takeIf { version < 2 && it > 0 }
        val childStart = entry.start + 36 + when (version) {
            1 -> 16
            2 -> 36
            else -> 0
        }
        val children = boxes(h, childStart, entry.end)
        fun child(name: String) = children.firstOrNull { it.type == name }
        return when (type) {
            "ec-3" -> {
                val dec3 = child("dec3") ?: return ProbeResult(Codec.EAC3, sampleRate = entryRate)
                val (channels, joc) = dec3(h, dec3.body, dec3.end)
                ProbeResult(Codec.EAC3, sampleRate = entryRate, channels = channels, atmos = joc)
            }
            "ac-3" -> {
                val channels = child("dac3")?.let { box ->
                    val bits = BitReader(h, box.body, box.end)
                    bits.skip(2 + 5 + 3)
                    val acmod = bits.read(3)
                    ACMOD_CHANNELS[acmod] + bits.read(1)
                }
                ProbeResult(Codec.AC3, sampleRate = entryRate, channels = channels, atmos = false)
            }
            "alac" -> {
                val config = child("alac") ?: return ProbeResult(Codec.ALAC, sampleRate = entryRate, channels = entryChannels)
                val c = config.body + 4
                ProbeResult(
                    Codec.ALAC,
                    sampleRate = u32be(h, c + 20).toInt(),
                    bitDepth = h[c + 5].u(),
                    channels = h[c + 9].u(),
                )
            }
            "fLaC" -> child("dfLa")?.let { box -> flac(h, box.body + 4) } ?: ProbeResult(Codec.FLAC, sampleRate = entryRate)
            "mp4a" -> ProbeResult(Codec.AAC, sampleRate = entryRate, channels = entryChannels)
            "Opus" -> ProbeResult(Codec.OPUS, sampleRate = entryRate, channels = entryChannels)
            "mlpa" -> ProbeResult(Codec.TRUEHD, channels = entryChannels)
            else -> null
        }
    }

    private val MP4_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")
    private val MP4_AUDIO = setOf("mp4a", "alac", "ec-3", "ac-3", "fLaC", "Opus", "mlpa")

    private fun findSampleEntry(h: ByteArray, from: Int, to: Int): Box? {
        for (box in boxes(h, from, to)) {
            when (box.type) {
                in MP4_CONTAINERS -> findSampleEntry(h, box.body, box.end)?.let { return it }
                "stsd" -> boxes(h, box.body + 8, box.end).firstOrNull { it.type in MP4_AUDIO }?.let { return it }
            }
        }
        return null
    }

    /** ETSI TS 102 366 Annex F: channels of the first substream, and flag_ec3_extension_type_a (JOC). */
    private fun dec3(h: ByteArray, from: Int, to: Int): Pair<Int?, Boolean> {
        val bits = BitReader(h, from, to)
        bits.skip(13) // data_rate
        val independent = bits.read(3) + 1
        var channels: Int? = null
        repeat(independent) { i ->
            bits.skip(2 + 5 + 1 + 1 + 3) // fscod, bsid, reserved, asvc, bsmod
            val acmod = bits.read(3)
            val lfe = bits.read(1)
            bits.skip(3)
            val dependent = bits.read(4)
            var count = ACMOD_CHANNELS[acmod] + lfe
            if (dependent > 0) {
                if (bits.read(9) and 0x02 != 0) count += 2 // Lrs/Rrs pair
            } else {
                bits.skip(1)
            }
            if (i == 0) channels = count
        }
        if (bits.remaining < 8) return channels to false
        bits.skip(7)
        return channels to bits.bool()
    }

    // ---- Matroska ----

    private val MKV_CODECS = listOf(
        "A_TRUEHD" to Codec.TRUEHD,
        "A_EAC3" to Codec.EAC3,
        "A_AC3" to Codec.AC3,
        "A_DTS" to Codec.DTS,
        "A_FLAC" to Codec.FLAC,
        "A_ALAC" to Codec.ALAC,
        "A_AAC" to Codec.AAC,
        "A_OPUS" to Codec.OPUS,
        "A_VORBIS" to Codec.VORBIS,
        "A_MPEG/L3" to Codec.MP3,
        "A_PCM" to Codec.WAV,
    )
    private val MKV_CLUSTER = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)

    private fun matroska(h: ByteArray): ProbeResult? {
        val codec = MKV_CODECS.firstOrNull { (id, _) -> indexOf(h, id.toByteArray(), 0, h.size) >= 0 }?.second ?: return null
        // Frames live in clusters; searching from the first one avoids matching bytes in cover art.
        val cluster = indexOf(h, MKV_CLUSTER, 0, h.size).coerceAtLeast(0)
        return when (codec) {
            Codec.TRUEHD -> indexOf(h, TRUEHD_SYNC, cluster, h.size).takeIf { it >= 0 }?.let { trueHd(h, it) }
                ?: ProbeResult(Codec.TRUEHD)
            Codec.EAC3, Codec.AC3 -> firstDolbyFrame(h, cluster, codec) ?: ProbeResult(codec)
            Codec.FLAC -> indexOf(h, "fLaC".toByteArray(), 0, h.size).takeIf { it >= 0 }?.let { flac(h, it + 4) }
                ?: ProbeResult(Codec.FLAC)
            else -> ProbeResult(codec)
        }
    }

    // ---- Dolby bitstreams ----

    private fun firstDolbyFrame(h: ByteArray, from: Int, codec: Codec): ProbeResult? {
        var p = indexOf(h, AC3_SYNC_BYTES, from, h.size)
        while (p >= 0) {
            val frame = try {
                dolbyFrame(h, p)
            } catch (e: IndexOutOfBoundsException) {
                null
            }
            if (frame != null && frame.codec == codec) return frame
            p = indexOf(h, AC3_SYNC_BYTES, p + 2, h.size)
        }
        return null
    }

    /**
     * An AC-3 or E-AC-3 sync frame at [p]. For E-AC-3 the bit stream info is walked (as in
     * ETSI TS 102 366 E.1.2.2) up to addbsi, whose first byte carries the Atmos/JOC flag.
     */
    private fun dolbyFrame(h: ByteArray, p: Int): ProbeResult? {
        val bsid = h[p + 5].u() ushr 3
        if (bsid <= 10) {
            val bits = BitReader(h, p + 4)
            val fscod = bits.read(2)
            bits.skip(6 + 5 + 3) // frmsizecod, bsid, bsmod
            val acmod = bits.read(3)
            if (acmod and 1 != 0 && acmod != 1) bits.skip(2)
            if (acmod and 4 != 0) bits.skip(2)
            if (acmod == 2) bits.skip(2)
            val lfe = bits.read(1)
            return ProbeResult(Codec.AC3, sampleRate = AC3_RATES.getOrNull(fscod), channels = ACMOD_CHANNELS[acmod] + lfe, atmos = false)
        }
        if (bsid > 16) return null
        val bits = BitReader(h, p + 2)
        val streamType = bits.read(2)
        if (streamType == 3) return null
        bits.skip(3 + 11) // substreamid, frmsiz
        val fscod = bits.read(2)
        val blocksCode: Int
        val rate: Int?
        if (fscod == 3) {
            rate = AC3_RATES.getOrNull(bits.read(2))?.div(2)
            blocksCode = 3
        } else {
            rate = AC3_RATES[fscod]
            blocksCode = bits.read(2)
        }
        val acmod = bits.read(3)
        val lfe = bits.bool()
        bits.skip(5 + 5) // bsid, dialnorm
        if (bits.bool()) bits.skip(8) // compr
        if (acmod == 0) {
            bits.skip(5)
            if (bits.bool()) bits.skip(8)
        }
        if (streamType == 1 && bits.bool()) bits.skip(16) // chanmap
        if (bits.bool()) { // mixmdate
            if (acmod > 2) bits.skip(2)
            if (acmod and 1 != 0 && acmod > 2) bits.skip(6)
            if (acmod and 4 != 0) bits.skip(6)
            if (lfe && bits.bool()) bits.skip(5)
            if (streamType == 0) {
                if (bits.bool()) bits.skip(6)
                if (acmod == 0 && bits.bool()) bits.skip(6)
                if (bits.bool()) bits.skip(6)
                when (bits.read(2)) {
                    1 -> bits.skip(5)
                    2 -> bits.skip(12)
                    3 -> bits.skip((bits.read(5) + 2) * 8)
                }
                if (acmod < 2) {
                    if (bits.bool()) bits.skip(14)
                    if (acmod == 0 && bits.bool()) bits.skip(14)
                }
                if (bits.bool()) { // frmmixcfginfoe
                    if (blocksCode == 0) {
                        bits.skip(5)
                    } else {
                        repeat(BLOCKS[blocksCode]) { if (bits.bool()) bits.skip(5) }
                    }
                }
            }
        }
        if (bits.bool()) { // infomdate
            bits.skip(5)
            if (acmod == 2) bits.skip(4)
            if (acmod >= 6) bits.skip(2)
            if (bits.bool()) bits.skip(8)
            if (acmod == 0 && bits.bool()) bits.skip(8)
            if (fscod < 3) bits.skip(1)
        }
        if (streamType == 0 && blocksCode != 3) bits.skip(1) // convsync
        if (streamType == 2 && (blocksCode == 3 || bits.bool())) bits.skip(6) // blkid, frmsizecod
        val joc = bits.bool() && bits.read(6) >= 1 && bits.read(8) and 1 == 1
        return ProbeResult(Codec.EAC3, sampleRate = rate, channels = ACMOD_CHANNELS[acmod] + if (lfe) 1 else 0, atmos = joc)
    }

    /**
     * A TrueHD major sync at [sync]. Atmos adds a fourth substream carrying the 16-channel
     * presentation; plain 5.1/7.1 TrueHD has at most three.
     */
    private fun trueHd(h: ByteArray, sync: Int): ProbeResult? {
        if (u16be(h, sync + 8) != 0xB752) return null
        val rateBits = h[sync + 4].u() ushr 4
        val rate = (if (rateBits and 8 != 0) 44_100 else 48_000) shl (rateBits and 7)
        val substreams = h[sync + 16].u() ushr 4
        return ProbeResult(Codec.TRUEHD, sampleRate = rate, atmos = substreams == 4)
    }

    // ---- others ----

    private fun ogg(h: ByteArray): ProbeResult? {
        indexOf(h, "OpusHead".toByteArray(), 0, minOf(h.size, 512)).takeIf { it >= 0 }?.let {
            return ProbeResult(Codec.OPUS, sampleRate = u32le(h, it + 12).toInt(), channels = h[it + 9].u())
        }
        indexOf(h, "\u0001vorbis".toByteArray(), 0, minOf(h.size, 512)).takeIf { it >= 0 }?.let {
            return ProbeResult(Codec.VORBIS, sampleRate = u32le(h, it + 12).toInt(), channels = h[it + 11].u())
        }
        indexOf(h, "fLaC".toByteArray(), 0, minOf(h.size, 512)).takeIf { it >= 0 }?.let { return flac(h, it + 4) }
        return null
    }

    private fun isMpegAudioSync(h: ByteArray, p: Int) = h[p].u() == 0xFF && h[p + 1].u() and 0xE0 == 0xE0

    private fun mpegAudio(h: ByteArray, p: Int): ProbeResult =
        // Layer bits 00 mark ADTS AAC; anything else is MPEG audio (MP3).
        if ((h[p + 1].u() ushr 1) and 3 == 0) ProbeResult(Codec.AAC) else ProbeResult(Codec.MP3)

    private fun id3Size(h: ByteArray): Int {
        if (!ascii(h, 0, "ID3") || h.size < 10) return 0
        val size = (h[6].u() and 0x7F shl 21) or (h[7].u() and 0x7F shl 14) or (h[8].u() and 0x7F shl 7) or (h[9].u() and 0x7F)
        val footer = if (h[5].u() and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    /** IEEE 754 80-bit extended float (AIFF sample rates). */
    private fun extended80(h: ByteArray, p: Int): Double {
        val exponent = ((h[p].u() and 0x7F) shl 8) or h[p + 1].u()
        val mantissa = (u32be(h, p + 2) shl 32) or u32be(h, p + 6)
        if (exponent == 0 && mantissa == 0L) return 0.0
        return Math.scalb((mantissa ushr 11).toDouble(), exponent - 16383 - 52)
    }

    private fun ascii(h: ByteArray, p: Int, text: String): Boolean =
        p >= 0 && p + text.length <= h.size && text.indices.all { h[p + it].toInt().toChar() == text[it] }

    private fun indexOf(h: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        val last = minOf(to, h.size) - needle.size
        var i = from.coerceAtLeast(0)
        while (i <= last) {
            if (h[i] == needle[0] && needle.indices.all { h[i + it] == needle[it] }) return i
            i++
        }
        return -1
    }

    private fun Byte.u() = toInt() and 0xFF
    private fun u16be(h: ByteArray, p: Int) = (h[p].u() shl 8) or h[p + 1].u()
    private fun u16le(h: ByteArray, p: Int) = h[p].u() or (h[p + 1].u() shl 8)
    private fun u32be(h: ByteArray, p: Int): Long =
        (h[p].u().toLong() shl 24) or (h[p + 1].u().toLong() shl 16) or (h[p + 2].u().toLong() shl 8) or h[p + 3].u().toLong()
    private fun u32le(h: ByteArray, p: Int): Long =
        h[p].u().toLong() or (h[p + 1].u().toLong() shl 8) or (h[p + 2].u().toLong() shl 16) or (h[p + 3].u().toLong() shl 24)

    private const val AC3_SYNC = 0x0B77
    private val AC3_SYNC_BYTES = byteArrayOf(0x0B, 0x77)
    private val TRUEHD_SYNC = byteArrayOf(0xF8.toByte(), 0x72, 0x6F, 0xBA.toByte())
    private val ACMOD_CHANNELS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
    private val AC3_RATES = intArrayOf(48_000, 44_100, 32_000)
    private val BLOCKS = intArrayOf(1, 2, 3, 6)
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val MAX_MOOV = 16L * 1024 * 1024
}

/** Reads big-endian bit fields. */
internal class BitReader(private val data: ByteArray, start: Int, end: Int = data.size) {
    private var position = start.toLong() * 8
    private val limit = minOf(end, data.size).toLong() * 8

    val remaining: Long get() = limit - position

    fun read(count: Int): Int {
        if (count > remaining) throw IndexOutOfBoundsException("Bit stream ended")
        var value = 0
        repeat(count) {
            val byte = data[(position ushr 3).toInt()].toInt()
            value = (value shl 1) or ((byte ushr (7 - (position and 7).toInt())) and 1)
            position++
        }
        return value
    }

    fun bool(): Boolean = read(1) == 1

    fun skip(count: Int) {
        if (count > remaining) throw IndexOutOfBoundsException("Bit stream ended")
        position += count
    }
}
