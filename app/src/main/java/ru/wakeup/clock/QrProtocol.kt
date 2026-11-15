package ru.wakeup.clock

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Station(val id: String, val secret: String)

/** Platform-independent protocol shared with web/protocol.js. */
object QrProtocol {
    private val idPattern = Regex("[a-f0-9]{16}")
    private val secretPattern = Regex("[a-f0-9]{64}")
    private val pairPattern = Regex("wakeup://pair/v1/([a-f0-9]{16})/([a-f0-9]{64})")
    private val dismissPattern = Regex("wakeup://dismiss/v1/([a-f0-9]{16})/([0-9]{1,12})/([a-f0-9]{64})")

    fun parsePair(value: String): Station? {
        if (value.length > 200) return null
        val parts = pairPattern.matchEntire(value)?.groupValues ?: return null
        return Station(parts[1], parts[2])
    }

    fun signature(station: Station, seconds: Long): String {
        require(idPattern.matches(station.id) && secretPattern.matches(station.secret))
        val bytes = station.secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(bytes, "HmacSHA256"))
        return mac.doFinal("wakeup:v1:${station.id}:$seconds".toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun verify(value: String, station: Station, nowMillis: Long, startedAtMillis: Long): Boolean {
        if (value.length > 220) return false
        val parts = dismissPattern.matchEntire(value)?.groupValues ?: return false
        if (parts[1] != station.id) return false
        val seconds = parts[2].toLongOrNull() ?: return false
        if (seconds % 30 != 0L) return false
        val now = nowMillis / 1000
        if (seconds < now - 45 || seconds > now + 5 || seconds < startedAtMillis / 1000 - 30) return false
        return runCatching {
            MessageDigest.isEqual(signature(station, seconds).toByteArray(UTF_8), parts[3].toByteArray(UTF_8))
        }.getOrDefault(false)
    }
}
