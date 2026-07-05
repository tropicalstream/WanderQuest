package com.tropicalstream.wanderquest.game

import java.util.zip.CRC32

/**
 * Wander Duels — competition with zero servers and zero accounts.
 *
 * How it works (kid-and-parent friendly):
 *  1. Players agree on a CODE (any word, e.g. "FOXFIRE") and each enters it.
 *     Entering the code starts a 24-hour duel on each player's glasses.
 *  2. During the duel, special DUEL RELICS spawn (3x coins) and every coin
 *     + every 10 paces scores duel points.
 *  3. When the duel ends, the game shows your RESULT CODE — a short token
 *     like `WQ-K7M2X-4F` that encodes your score, checksummed against the
 *     shared duel code so it can't be fudged by typos (or fibbing little
 *     brothers — the checksum only matches if they really got that score
 *     under the same duel code).
 *  4. Exchange result codes (read it out loud, text it from a parent's
 *     phone, whatever) and type your rival's code in: the app verifies it,
 *     declares the winner with fanfare, and adds the rival to your
 *     leaderboard page.
 *
 * Works across cities; no network needed beyond humans talking.
 */
object DuelManager {

    const val DUEL_DURATION_MS = 24L * 3600L * 1000L
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun normalizeCode(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }.take(12)

    private fun crc(s: String): Long {
        val c = CRC32()
        c.update(s.toByteArray())
        return c.value
    }

    private fun toBase36(value: Long, minWidth: Int): String {
        var v = value
        val sb = StringBuilder()
        if (v == 0L) sb.append('0')
        while (v > 0) {
            sb.append(ALPHABET[(v % 36).toInt()])
            v /= 36
        }
        while (sb.length < minWidth) sb.append('0')
        return sb.reverse().toString()
    }

    private fun fromBase36(s: String): Long? {
        var v = 0L
        for (ch in s) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0) return null
            v = v * 36 + idx
        }
        return v
    }

    /** Build MY result code for a finished duel. */
    fun resultCode(duelCode: String, score: Long): String {
        val capped = score.coerceIn(0, 60_466_175) // 36^5 - 1, 5 chars
        val payload = toBase36(capped, 5)
        val check = crc("WQ:" + normalizeCode(duelCode) + ":" + capped) % 1296
        return "WQ-$payload-${toBase36(check, 2)}"
    }

    /**
     * Verify a rival's result code against the shared duel code.
     * Returns the rival's score, or null if the code doesn't check out.
     */
    fun verifyResultCode(duelCode: String, raw: String): Long? {
        val cleaned = raw.uppercase().filter { it.isLetterOrDigit() || it == '-' }
        val parts = cleaned.removePrefix("WQ-").split("-")
        if (parts.size != 2) return null
        val payload = parts[0]
        val checkStr = parts[1]
        if (payload.length != 5 || checkStr.length != 2) return null
        val score = fromBase36(payload) ?: return null
        val check = fromBase36(checkStr) ?: return null
        val expected = crc("WQ:" + normalizeCode(duelCode) + ":" + score) % 1296
        return if (check == expected) score else null
    }
}
