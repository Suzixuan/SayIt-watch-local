package com.sayit.watch.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextPolicyTest {

    @Test
    fun `debug build allows cleartext`() {
        assertTrue(CleartextPolicy.allowsCleartext(true))
        assertTrue(CleartextPolicy.createSender(cleartextAllowed = true) != null)
    }

    @Test
    fun `release build denies cleartext`() {
        assertTrue(!CleartextPolicy.allowsCleartext(false))
        assertNull(CleartextPolicy.createSender(cleartextAllowed = false))
    }

    @Test
    fun `release sender never exposes usable http client`() {
        // Simulates release: factory refuses to build a sender.
        val sender = CleartextPolicy.createSender(cleartextAllowed = false)
        assertNull(sender)
        // Even if a client were constructed with cleartext denied, upload must fail closed.
        val denied = TransportClient(cleartextAllowed = false)
        val result = denied.upload("192.168.1.5", 8080, "t".repeat(32), ByteArray(44))
        assertTrue(result is TransportClient.UploadResult.Failure)
        assertTrue((result as TransportClient.UploadResult.Failure).reason.contains("disabled"))
    }
}
