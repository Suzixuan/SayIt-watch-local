package com.sayit.watch.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavWriterTest {

    @Test
    fun `canonical header is exactly 44 bytes`() {
        val out = ByteArrayOutputStream()
        WavWriter.writeHeader(out, 0)
        assertEquals(WavWriter.HEADER_SIZE, out.size())
    }

    @Test
    fun `header contains RIFF WAVE fmt data and correct fields`() {
        val out = ByteArrayOutputStream()
        WavWriter.writeHeader(out, 1000)
        val bytes = out.toByteArray()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))

        // fmt chunk: PCM(1), mono(1), 16000 Hz, byteRate 32000, blockAlign 2, 16 bits
        assertEquals(16, leInt(bytes, 16)) // fmt chunk size
        assertEquals(1, leShort(bytes, 20)) // audio format = PCM
        assertEquals(1, leShort(bytes, 22)) // channels
        assertEquals(16000, leInt(bytes, 24)) // sample rate
        assertEquals(32000, leInt(bytes, 28)) // byte rate
        assertEquals(2, leShort(bytes, 32)) // block align
        assertEquals(16, leShort(bytes, 34)) // bits per sample

        // RIFF chunk size = 36 + data, data chunk size = data
        assertEquals(36 + 1000, leInt(bytes, 4))
        assertEquals(1000, leInt(bytes, 40))
    }

    @Test
    fun `sample-derived duration matches floor division`() {
        assertEquals(0L, WavWriter.durationMs(0))
        assertEquals(62L, WavWriter.durationMs(1000)) // 1000/16000 s = 62 ms
        assertEquals(1000L, WavWriter.durationMs(16000))
        assertEquals(10_000L, WavWriter.durationMs(160_000))
    }

    @Test
    fun `buildWav produces full RIFF WAV with exact payload`() {
        val pcm = ByteArray(640) // 320 samples of 16-bit
        for (i in pcm.indices) pcm[i] = (i % 251).toByte()
        val wav = WavWriter.buildWav(pcm, pcm.size)
        assertEquals(WavWriter.HEADER_SIZE + pcm.size, wav.size)
        // data chunk size field
        assertEquals(pcm.size, leInt(wav, 40))
        // payload identical
        for (i in pcm.indices) assertEquals(pcm[i], wav[WavWriter.HEADER_SIZE + i])
    }

    @Test
    fun `buildWav rejects odd pcm payload`() {
        val pcm = ByteArray(3)
        try {
            WavWriter.buildWav(pcm, pcm.size)
            assertTrue("expected IllegalArgumentException", false)
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
