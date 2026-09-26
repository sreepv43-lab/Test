package io.github.sreepv43.soundhub.slsk

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Soulseek messages are little-endian; strings and byte blobs are prefixed with a uint32 length. */
internal class MessageWriter {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int) = apply { out.write(value and 0xFF) }

    fun u32(value: Int) = apply { for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF) }

    fun u64(value: Long) = apply { for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt()) }

    fun bool(value: Boolean) = u8(if (value) 1 else 0)

    fun str(value: String) = bytes(value.toByteArray(Charsets.UTF_8))

    fun bytes(value: ByteArray) = apply {
        u32(value.size)
        out.write(value)
    }

    fun raw(value: ByteArray) = apply { out.write(value) }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class MessageReader(private val data: ByteArray, private var pos: Int = 0) {
    val remaining: Int get() = data.size - pos

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val value = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return value
    }

    fun u32(): Int {
        need(4)
        var value = 0
        for (i in 0 until 4) value = value or ((data[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return value
    }

    fun u64(): Long {
        need(8)
        var value = 0L
        for (i in 0 until 8) value = value or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return value
    }

    fun bool(): Boolean = u8() != 0

    fun str(): String {
        val length = u32()
        if (length < 0 || length > remaining) throw ProtocolException("Bad string length $length")
        val value = decodeText(data, pos, length)
        pos += length
        return value
    }

    fun bytes(): ByteArray {
        val length = u32()
        if (length < 0 || length > remaining) throw ProtocolException("Bad byte array length $length")
        return data.copyOfRange(pos, pos + length).also { pos += length }
    }

    fun rest(): ByteArray = data.copyOfRange(pos, data.size).also { pos = data.size }

    private fun need(count: Int) {
        if (remaining < count) throw ProtocolException("Message too short")
    }
}

class ProtocolException(message: String) : IOException(message)

/** Server and peer messages: uint32 length, uint32 code, payload. */
internal fun message(code: Int, payload: ByteArray = ByteArray(0)): ByteArray =
    MessageWriter().u32(payload.size + 4).u32(code).raw(payload).toByteArray()

/** Peer init messages (the first message on a new peer connection): uint32 length, uint8 code, payload. */
internal fun initMessage(code: Int, payload: ByteArray): ByteArray =
    MessageWriter().u32(payload.size + 1).u8(code).raw(payload).toByteArray()

/** Reads one length-prefixed frame (everything after the length). */
internal fun InputStream.readFrame(maxSize: Int): ByteArray {
    val header = readExactly(4)
    val length = MessageReader(header).u32()
    if (length < 1 || length > maxSize) throw ProtocolException("Bad frame length $length")
    return readExactly(length)
}

internal fun InputStream.readExactly(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(buffer, read, count - read)
        if (n < 0) throw EOFException("Connection closed")
        read += n
    }
    return buffer
}

internal fun zlibCompress(data: ByteArray): ByteArray {
    val deflater = Deflater()
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

internal fun zlibDecompress(data: ByteArray, maxSize: Int): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw ProtocolException("Truncated compressed data")
            out.write(buffer, 0, n)
            if (out.size() > maxSize) throw ProtocolException("Compressed message too large")
        }
        return out.toByteArray()
    } catch (e: java.util.zip.DataFormatException) {
        throw ProtocolException("Bad compressed data")
    } finally {
        inflater.end()
    }
}

/** Text is UTF-8 in current clients; some old Windows clients still send Latin-1. */
private fun decodeText(data: ByteArray, offset: Int, length: Int): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(data, offset, length))
        .toString()
} catch (e: CharacterCodingException) {
    String(data, offset, length, Charsets.ISO_8859_1)
}
