package com.sayit.watch.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevTokenValidatorTest {

    private val valid64 = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"

    @Test
    fun `accepts exactly 64 hex characters`() {
        assertTrue(DevTokenValidator.isValid(valid64))
        assertTrue(DevTokenValidator.isValid(valid64.uppercase()))
        assertTrue(DevTokenValidator.isValid("0".repeat(64)))
        assertTrue(DevTokenValidator.isValid("f".repeat(64)))
    }

    @Test
    fun `rejects 63 and 65 characters`() {
        assertFalse(DevTokenValidator.isValid(valid64.dropLast(1))) // 63
        assertFalse(DevTokenValidator.isValid(valid64 + "0")) // 65
        assertFalse(DevTokenValidator.isValid("")) // 0
        assertFalse(DevTokenValidator.isValid("a".repeat(32))) // too short
    }

    @Test
    fun `rejects non-hex characters`() {
        assertFalse(DevTokenValidator.isValid("g".repeat(64)))
        assertFalse(DevTokenValidator.isValid("z".repeat(64)))
        assertFalse(DevTokenValidator.isValid("-".repeat(64)))
        assertFalse(DevTokenValidator.isValid(" ".repeat(64)))
        assertFalse(DevTokenValidator.isValid(valid64.dropLast(1) + "g"))
    }

    @Test
    fun `trims surrounding whitespace then validates`() {
        assertTrue(DevTokenValidator.isValid("  $valid64"))
        assertTrue(DevTokenValidator.isValid("$valid64  "))
        assertTrue(DevTokenValidator.isValid(" \t $valid64 \n "))
    }

    @Test
    fun `canonical returns trimmed value only when valid`() {
        assertEquals(valid64, DevTokenValidator.canonicalOrNull("  $valid64  "))
        assertNull(DevTokenValidator.canonicalOrNull(valid64.dropLast(1)))
        assertNull(DevTokenValidator.canonicalOrNull("g".repeat(64)))
        assertNull(DevTokenValidator.canonicalOrNull(null))
    }

    private fun assertEquals(expected: String, actual: String?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
