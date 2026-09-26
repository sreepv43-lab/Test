package io.github.sreepv43.soundhub.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class AudioProbeTest {
    @Test
    fun flacStreamInfo() {
        val result = AudioProbe.probe(flac(rate = 96_000, channels = 2, bits = 24, samples = 96_000L * 200))!!
        assertEquals(ProbeResult(Codec.FLAC, 96_000, 24, 2, 200), result)
    }

    @Test
    fun flacAfterAnId3Tag() {
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val result = AudioProbe.probe(id3 + flac(44_100, 2, 16, 44_100L * 10))!!
        assertEquals(16, result.bitDepth)
        assertEquals(44_100, result.sampleRate)
    }

    @Test
    fun wavFormatChunk() {
        val out = Bytes()
        out.ascii("RIFF").le32(0).ascii("WAVE")
        out.ascii("fmt ").le32(16).le16(1).le16(2).le32(44_100).le32(44_100 * 4).le16(4).le16(16)
        out.ascii("data").le32(44_100 * 4 * 30)
        assertEquals(ProbeResult(Codec.WAV, 44_100, 16, 2, 30), AudioProbe.probe(out.bytes()))
    }

    @Test
    fun aiffCommonChunk() {
        val out = Bytes()
        out.ascii("FORM").be32(0).ascii("AIFF")
        // 44100 as an 80-bit extended float: exponent 16398 (2^15), mantissa 44100 << 48.
        out.ascii("COMM").be32(18).be16(2).be32(44_100 * 60).be16(24).be16(0x400E).be32((44_100L shl 16).toInt()).be32(0)
        assertEquals(ProbeResult(Codec.AIFF, 44_100, 24, 2, 60), AudioProbe.probe(out.bytes()))
    }

    @Test
    fun dsf() {
        val out = Bytes()
        out.ascii("DSD ").le64(28).le64(0).le64(0)
        out.ascii("fmt ").le64(52).le32(1).le32(0).le32(2).le32(2).le32(5_644_800).le32(1).le64(5_644_800L * 100).le32(4096).le32(0)
        val result = AudioProbe.probe(out.bytes())!!
        assertEquals(ProbeResult(Codec.DSD, 5_644_800, 1, 2, 100), result)
        assertEquals("DSD128", AudioInfo(Codec.DSD, "dsf").withProbe(result).label)
    }

    @Test
    fun mp4DolbyDigitalPlusWithAtmos() {
        val result = AudioProbe.probe(mp4("ec-3", box("dec3", dec3(joc = true))))!!
        assertEquals(Codec.EAC3, result.codec)
        assertEquals(true, result.atmos)
        assertEquals(6, result.channels)
        assertEquals(48_000, result.sampleRate)
    }

    @Test
    fun mp4DolbyDigitalPlusWithoutAtmos() {
        assertEquals(false, AudioProbe.probe(mp4("ec-3", box("dec3", dec3(joc = false))))!!.atmos)
    }

    @Test
    fun mp4Alac() {
        val config = Bytes().be32(0).be32(4096).u8(0).u8(24).u8(40).u8(10).u8(14).u8(2).be16(255).be32(0).be32(0).be32(96_000)
        val result = AudioProbe.probe(mp4("alac", box("alac", config.bytes())))!!
        assertEquals(ProbeResult(Codec.ALAC, 96_000, 24, 2), result)
    }

    @Test
    fun mp4Aac() {
        assertEquals(Codec.AAC, AudioProbe.probe(mp4("mp4a", box("esds", ByteArray(20))))!!.codec)
    }

    @Test
    fun mp4WithTheIndexAtTheEndIsUnknownFromTheStart() {
        val out = Bytes()
        out.raw(box("ftyp", "M4A ".toByteArray() + ByteArray(4)))
        out.be32(50_000_000).ascii("mdat").raw(ByteArray(1000))
        assertNull(AudioProbe.probe(out.bytes()))
    }

    @Test
    fun rawEac3FrameWithJoc() {
        val result = AudioProbe.probe(eac3Frame(joc = true))!!
        assertEquals(Codec.EAC3, result.codec)
        assertEquals(true, result.atmos)
        assertEquals(6, result.channels)
    }

    @Test
    fun rawEac3FrameWithoutJoc() {
        assertEquals(false, AudioProbe.probe(eac3Frame(joc = false))!!.atmos)
    }

    @Test
    fun trueHdAtmosNeedsTheFourthSubstream() {
        assertEquals(ProbeResult(Codec.TRUEHD, 48_000, atmos = true), AudioProbe.probe(trueHdUnit(substreams = 4)))
        assertEquals(false, AudioProbe.probe(trueHdUnit(substreams = 3))!!.atmos)
    }

    @Test
    fun matroskaTrueHdAtmos() {
        val result = AudioProbe.probe(mka("A_TRUEHD", trueHdUnit(substreams = 4)))!!
        assertEquals(Codec.TRUEHD, result.codec)
        assertEquals(true, result.atmos)
    }

    @Test
    fun matroskaEac3Atmos() {
        val result = AudioProbe.probe(mka("A_EAC3", eac3Frame(joc = true)))!!
        assertEquals(Codec.EAC3, result.codec)
        assertEquals(true, result.atmos)
    }

    @Test
    fun mp3() {
        assertEquals(Codec.MP3, AudioProbe.probe(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(100))!!.codec)
    }

    @Test
    fun garbageIsUnknown() {
        assertNull(AudioProbe.probe(ByteArray(10) { it.toByte() }))
        assertNull(AudioProbe.probe(ByteArray(0)))
    }

    // ---- builders ----

    private fun flac(rate: Int, channels: Int, bits: Int, samples: Long): ByteArray {
        val info = BitWriter()
        info.write(4096, 16).write(4096, 16).write(0, 24).write(0, 24)
        info.write(rate, 20).write(channels - 1, 3).write(bits - 1, 5).write((samples ushr 32).toInt(), 4).write(samples.toInt(), 32)
        info.write(0, 64).write(0, 64) // md5
        val streamInfo = info.bytes()
        return "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, streamInfo.size.toByte()) + streamInfo
    }

    private fun box(type: String, body: ByteArray): ByteArray = Bytes().be32(8 + body.size).ascii(type).raw(body).bytes()

    private fun mp4(entryType: String, child: ByteArray): ByteArray {
        val entry = Bytes().raw(ByteArray(6)).be16(1).raw(ByteArray(8)).be16(2).be16(16).be16(0).be16(0).be32(48_000 shl 16).raw(child)
        val stsd = Bytes().be32(0).be32(1).raw(box(entryType, entry.bytes()))
        val stbl = box("stbl", box("stsd", stsd.bytes()))
        val moov = box("moov", box("trak", box("mdia", box("minf", stbl))))
        return box("ftyp", "M4A ".toByteArray() + ByteArray(4)) + moov + box("mdat", ByteArray(64))
    }

    /** One independent 5.1 substream, optionally with flag_ec3_extension_type_a. */
    private fun dec3(joc: Boolean): ByteArray {
        val bits = BitWriter()
        bits.write(768, 13).write(0, 3)
        bits.write(0, 2).write(16, 5).write(0, 1).write(0, 1).write(0, 3).write(7, 3).write(1, 1).write(0, 3).write(0, 4).write(0, 1)
        if (joc) bits.write(0, 7).write(1, 1).write(16, 8)
        return bits.bytes()
    }

    private fun eac3Frame(joc: Boolean): ByteArray {
        val bits = BitWriter()
        bits.write(0x0B77, 16)
        bits.write(0, 2).write(0, 3).write(767, 11) // strmtyp, substreamid, frmsiz
        bits.write(0, 2).write(3, 2) // fscod 48 kHz, 6 blocks
        bits.write(7, 3).write(1, 1) // 3/2 + LFE
        bits.write(16, 5).write(27, 5).write(0, 1) // bsid, dialnorm, compre
        bits.write(0, 1) // mixmdate
        bits.write(0, 1) // infomdate
        if (joc) {
            bits.write(1, 1).write(1, 6).write(0x01, 8).write(0x10, 8) // addbsi: JOC flag + complexity
        } else {
            bits.write(0, 1)
        }
        return bits.bytes() + ByteArray(1536)
    }

    private fun trueHdUnit(substreams: Int): ByteArray = Bytes()
        .be16(0x1000).be16(0) // access unit header
        .raw(byteArrayOf(0xF8.toByte(), 0x72, 0x6F, 0xBA.toByte()))
        .be32(0x0000_0000) // format_info: 48 kHz
        .be16(0xB752).be16(0).be16(0).be16(0x8000)
        .u8(substreams shl 4).u8(0x80)
        .raw(ByteArray(64))
        .bytes()

    private fun mka(codecId: String, frame: ByteArray): ByteArray = Bytes()
        .be32(0x1A45DFA3).raw(ByteArray(20))
        .u8(0x86).u8(0x80 or codecId.length).ascii(codecId)
        .raw(ByteArray(100) { 0x55 })
        .be32(0x1F43B675).raw(ByteArray(12))
        .raw(frame)
        .bytes()

    private class Bytes {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v) }
        fun be16(v: Int) = apply { out.write(v ushr 8); out.write(v) }
        fun be32(v: Int) = apply { be16(v ushr 16); be16(v) }
        fun le16(v: Int) = apply { out.write(v); out.write(v ushr 8) }
        fun le32(v: Int) = apply { le16(v); le16(v ushr 16) }
        fun le64(v: Long) = apply { le32(v.toInt()); le32((v ushr 32).toInt()) }
        fun ascii(s: String) = apply { out.write(s.toByteArray()) }
        fun raw(b: ByteArray) = apply { out.write(b) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class BitWriter {
        private val bits = ArrayList<Boolean>()
        fun write(value: Int, count: Int) = apply { for (i in count - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
        fun bytes(): ByteArray = ByteArray((bits.size + 7) / 8) { byte ->
            var v = 0
            for (i in 0 until 8) v = (v shl 1) or if (bits.getOrElse(byte * 8 + i) { false }) 1 else 0
            v.toByte()
        }
    }
}
