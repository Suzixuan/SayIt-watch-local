package com.sayit.watch.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportStatusTest {

    private val client = TransportClient(cleartextAllowed = true)

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
        val result = client.upload("8.8.8.8", 80, "t".repeat(32), ByteArray(44))
        assertTrue(result is TransportClient.UploadResult.Failure)
        assertTrue((result as TransportClient.UploadResult.Failure).reason.contains("RFC1918"))
    }

    @Test
    fun `short dev token fails closed`() {
        val result = client.upload("192.168.1.5", 8080, "short", ByteArray(44))
        assertTrue(result is TransportClient.UploadResult.Failure)
        assertTrue((result as TransportClient.UploadResult.Failure).reason.contains("32"))
    }

    @Test
    fun `cleartext denied fails before any network io`() {
        val denied = TransportClient(cleartextAllowed = false)
        val result = denied.upload("192.168.1.5", 8080, "t".repeat(32), ByteArray(44))
        assertTrue(result is TransportClient.UploadResult.Failure)
        assertTrue((result as TransportClient.UploadResult.Failure).reason.contains("disabled"))
    }
}
