package com.sayit.watch.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportStatusTest {

    private val client = TransportClient(cleartextAllowed = true)

    private val validToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
    private val testUuid = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `only 201 maps to transport success`() {
        assertTrue(client.isTransportSuccess(201))
        // 202 Accepted is explicitly NOT transport success.
        assertFalse(client.isTransportSuccess(202))
        assertFalse(client.isTransportSuccess(200))
        assertFalse(client.isTransportSuccess(204))
        assertFalse(client.isTransportSuccess(400))
        assertFalse(client.isTransportSuccess(401))
        assertFalse(client.isTransportSuccess(413))
        assertFalse(client.isTransportSuccess(500))
        assertFalse(client.isTransportSuccess(0))
    }

    @Test
    fun `destination validation failure is a failure not a success`() {
        val result = client.upload("8.8.8.8", 80, validToken, ByteArray(44))
        assertTrue(result is TransportClient.UploadResult.Failure)
        assertTrue((result as TransportClient.UploadResult.Failure).reason.contains("RFC1918"))
    }

    @Test
    fun `non-64-hex dev token fails closed`() {
        // too short
        val short = client.upload("192.168.1.5", 8080, "short", ByteArray(44))
        assertTrue(short is TransportClient.UploadResult.Failure)
        assertTrue((short as TransportClient.UploadResult.Failure).reason.contains("64 hex"))
        // 63 chars
        val c63 = client.upload("192.168.1.5", 8080, validToken.dropLast(1), ByteArray(44))
        assertTrue(c63 is TransportClient.UploadResult.Failure)
        // 65 chars
        val c65 = client.upload("192.168.1.5", 8080, validToken + "0", ByteArray(44))
        assertTrue(c65 is TransportClient.UploadResult.Failure)
        // non-hex
        val nonHex = client.upload("192.168.1.5", 8080, "g".repeat(64), ByteArray(44))
        assertTrue(nonHex is TransportClient.UploadResult.Failure)
    }

    @Test
    fun `cleartext denied fails before any network io`() {
        val denied = TransportClient(cleartextAllowed = false)
        val result = denied.upload("192.168.1.5", 8080, validToken, ByteArray(44))
        assertTrue(result is TransportClient.UploadResult.Failure)
        assertTrue((result as TransportClient.UploadResult.Failure).reason.contains("disabled"))
    }

    @Test
    fun `201 with matching requestId is success`() {
        val body = """{"requestId":"$testUuid","bytes":44,"sampleCount":0,"audioDurationMs":0,"sha256":"abc"}"""
        val result = client.verifySuccessResponse(testUuid, 201, body)
        assertTrue(result is TransportClient.UploadResult.Success)
        val success = result as TransportClient.UploadResult.Success
        assertEquals(testUuid, success.requestId)
    }

    @Test
    fun `201 with mismatched requestId is failure`() {
        val body = """{"requestId":"11111111-2222-3333-4444-555555555555","bytes":44,"sampleCount":0,"audioDurationMs":0,"sha256":"abc"}"""
        val result = client.verifySuccessResponse(testUuid, 201, body)
        assertTrue(result is TransportClient.UploadResult.Failure)
        assertTrue((result as TransportClient.UploadResult.Failure).reason.contains("mismatch"))
    }

    @Test
    fun `201 with missing requestId is failure`() {
        val body = """{"bytes":44,"sampleCount":0,"audioDurationMs":0,"sha256":"abc"}"""
        val result = client.verifySuccessResponse(testUuid, 201, body)
        assertTrue(result is TransportClient.UploadResult.Failure)
        assertTrue((result as TransportClient.UploadResult.Failure).reason.contains("missing requestId"))
    }

    @Test
    fun `non-201 with valid body is still failure`() {
        val body = """{"requestId":"$testUuid","bytes":44,"sampleCount":0,"audioDurationMs":0,"sha256":"abc"}"""
        val result = client.verifySuccessResponse(testUuid, 202, body)
        assertTrue(result is TransportClient.UploadResult.Failure)
        assertTrue((result as TransportClient.UploadResult.Failure).reason.contains("HTTP 202"))
    }

    @Test
    fun `malformed body is failure`() {
        val result = client.verifySuccessResponse(testUuid, 201, "not json at all")
        assertTrue(result is TransportClient.UploadResult.Failure)
    }
}
