package io.github.sreepv43.streamhub.server

import java.net.URLDecoder

object Magnets {
    /** The info-hash (40 hex characters) of a magnet link, from its hex or base32 "btih". */
    fun infoHash(magnet: String): String? {
        val xt = magnet.substringAfter('?', "").split('&')
            .filter { it.startsWith("xt=", ignoreCase = true) }
            .map { URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            .firstOrNull { it.startsWith("urn:btih:", ignoreCase = true) }
            ?.substring("urn:btih:".length) ?: return null
        return when {
            xt.length == 40 && xt.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> xt.lowercase()
            xt.length == 32 -> base32ToHex(xt.uppercase())
            else -> null
        }
    }

    private fun base32ToHex(text: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0L
        var count = 0
        val out = StringBuilder()
        for (c in text) {
            val value = alphabet.indexOf(c).takeIf { it >= 0 } ?: return null
            bits = (bits shl 5) or value.toLong()
            count += 5
            if (count >= 8) {
                count -= 8
                out.append("%02x".format(((bits shr count) and 0xff).toInt()))
            }
        }
        return out.toString().takeIf { it.length == 40 }
    }
}
