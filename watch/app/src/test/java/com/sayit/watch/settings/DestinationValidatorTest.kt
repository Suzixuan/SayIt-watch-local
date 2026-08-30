package com.sayit.watch.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationValidatorTest {

    private fun valid(ip: String, port: String) =
        DestinationValidator.validate(ip, port) is DestinationValidator.ValidationResult.Valid

    private fun reason(ip: String, port: String): String {
        val r = DestinationValidator.validate(ip, port)
        assertTrue("expected invalid, got $r", r is DestinationValidator.ValidationResult.Invalid)
        return (r as DestinationValidator.ValidationResult.Invalid).reason
    }

    @Test
    fun `accepts rfc1918 addresses`() {
        assertTrue(valid("10.0.0.1", "8080"))
        assertTrue(valid("10.255.255.255", "1"))
        assertTrue(valid("172.16.0.1", "65535"))
        assertTrue(valid("172.31.255.255", "443"))
        assertTrue(valid("192.168.1.100", "9090"))
        assertTrue(valid("192.168.0.1", "80"))
    }

    @Test
    fun `rejects hostnames, ipv6, and malformed`() {
        assertTrue(reason("my-pc", "8080").contains("IPv4"))
        assertTrue(reason("localhost", "8080").contains("IPv4"))
        assertTrue(reason("sayit.local", "8080").contains("IPv4"))
        assertTrue(reason("fe80::1", "8080").contains("IPv4"))
        assertTrue(reason("::1", "8080").contains("IPv4"))
        assertTrue(reason("192.168.1", "8080").contains("IPv4"))
        assertTrue(reason("192.168.1.999", "8080").contains("range"))
        assertTrue(reason("", "8080").contains("required"))
        assertTrue(reason("   ", "8080").contains("required"))
    }

    @Test
    fun `rejects loopback, wildcard, link-local`() {
        assertTrue(reason("127.0.0.1", "8080").contains("loopback"))
        assertTrue(reason("0.0.0.0", "8080").contains("wildcard"))
        assertTrue(reason("169.254.1.1", "8080").contains("link-local"))
    }

    @Test
    fun `rejects public and non-private addresses`() {
        assertTrue(reason("8.8.8.8", "8080").contains("RFC1918"))
        assertTrue(reason("1.1.1.1", "8080").contains("RFC1918"))
        assertTrue(reason("172.32.0.1", "8080").contains("RFC1918")) // outside 172.16/12
        assertTrue(reason("172.15.0.1", "8080").contains("RFC1918"))
        assertTrue(reason("192.169.1.1", "8080").contains("RFC1918"))
        assertTrue(reason("100.64.0.1", "8080").contains("RFC1918")) // CGNAT, not RFC1918
    }

    @Test
    fun `rejects invalid ports`() {
        assertTrue(reason("192.168.1.1", "0").contains("1-65535"))
        assertTrue(reason("192.168.1.1", "65536").contains("1-65535"))
        assertTrue(reason("192.168.1.1", "-1").contains("1-65535"))
        assertTrue(reason("192.168.1.1", "abc").contains("integer"))
        assertTrue(reason("192.168.1.1", "").contains("integer"))
        assertTrue(reason("192.168.1.1", "80.5").contains("integer"))
    }

    @Test
    fun `parses valid result fields`() {
        val r = DestinationValidator.validate("192.168.1.50", "9123")
        assertTrue(r is DestinationValidator.ValidationResult.Valid)
        val v = r as DestinationValidator.ValidationResult.Valid
        assertEquals("192.168.1.50", v.ip)
        assertEquals(9123, v.port)
    }

    @Test
    fun `leading zero octets normalize`() {
        val r = DestinationValidator.validate("192.168.001.010", "80")
        assertTrue(r is DestinationValidator.ValidationResult.Valid)
        assertEquals("192.168.1.10", (r as DestinationValidator.ValidationResult.Valid).ip)
    }
}
