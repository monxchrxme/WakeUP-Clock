package ru.wakeup.clock

import org.junit.Assert.*
import org.junit.Test

class QrProtocolTest {
    // Same published vector as shared/protocol-vector.json; generated independently with Python hmac.
    private val station = Station("0123456789abcdef", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val seconds = 1794740400L
    private val hash = "752cf754e8b964978c968fcd4a7b9fdc816484cfe4a57e70412dd441ad397ced"
    private val code = "wakeup://dismiss/v1/${station.id}/$seconds/$hash"
    private fun verify(value: String = code, age: Long = 5, started: Long = seconds) =
        QrProtocol.verify(value, station, (seconds + age) * 1000, started * 1000)

    @Test fun matchesBrowserAndIndependentHmacVector() { assertEquals(hash, QrProtocol.signature(station, seconds)) }
    @Test fun acceptsFreshCode() { assertTrue(verify()) }
    @Test fun acceptsExpiryBoundaryOnly() { assertTrue(verify(age = 45)); assertFalse(verify(age = 46)) }
    @Test fun allowsOnlySmallClockSkew() { assertTrue(verify(age = -5)); assertFalse(verify(age = -6)) }
    @Test fun rejectsEarlierAlarmCode() { assertFalse(verify(started = seconds + 31)) }
    @Test fun rejectsTampering() { assertFalse(verify(code.dropLast(1) + "0")) }
    @Test fun rejectsDifferentStation() { assertFalse(verify(code.replace(station.id, "ffffffffffffffff"))) }
    @Test fun rejectsPairCodeAsDismissal() { assertFalse(verify("wakeup://pair/v1/${station.id}/${station.secret}")) }
    @Test fun rejectsMalformedCodes() {
        listOf("", code + "/", code.uppercase(), code.replace("1794740400", "9999999999999999999999"), "x".repeat(1000))
            .forEach { assertFalse(it, verify(it)) }
    }
    @Test fun pairParserIsStrict() {
        val pair = "wakeup://pair/v1/${station.id}/${station.secret}"
        assertEquals(station, QrProtocol.parsePair(pair))
        assertNull(QrProtocol.parsePair(pair + "?key=anything"))
        assertNull(QrProtocol.parsePair(code))
    }
    @Test fun rejectsSignedButUnalignedTimestamp() {
        val t = seconds + 1
        val value = "wakeup://dismiss/v1/${station.id}/$t/${QrProtocol.signature(station, t)}"
        assertFalse(verify(value))
    }
}
