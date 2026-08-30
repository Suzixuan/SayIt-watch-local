package com.sayit.watch.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAccumulatorTest {

    @Test
    fun `exact partial read handling - only read samples are appended`() {
        val acc = PcmAccumulator()
        val buffer = ShortArray(1024)
        // Fill the whole buffer but only report 7 samples read (partial read).
        for (i in buffer.indices) buffer[i] = (i % 200 - 100).toShort()

        acc.append(buffer, 0, 7)
        assertEquals(7, acc.sampleCount)
        val pcm = acc.pcmBytes()
        assertEquals(14, pcm.size) // exactly 7 * 2 bytes, unused buffer bytes excluded

        // Verify byte order of the 7 captured samples.
        for (i in 0 until 7) {
            val s = buffer[i].toInt() and 0xFFFF
            assertEquals((s and 0xFF).toByte(), pcm[i * 2])
            assertEquals(((s ushr 8) and 0xFF).toByte(), pcm[i * 2 + 1])
        }
    }

    @Test
    fun `multiple reads accumulate in order with offset handling`() {
        val acc = PcmAccumulator()
        val buf = ShortArray(8)
        for (i in buf.indices) buf[i] = (i + 1).toShort() // 1..8

        acc.append(buf, 2, 3) // samples 3,4,5
        acc.append(buf, 6, 2) // samples 7,8
        assertEquals(5, acc.sampleCount)
        val pcm = acc.pcmBytes()
        assertEquals(10, pcm.size)

        val expected = shortArrayOf(3, 4, 5, 7, 8)
        for (i in expected.indices) {
            val s = expected[i].toInt() and 0xFFFF
            assertEquals((s and 0xFF).toByte(), pcm[i * 2])
            assertEquals(((s ushr 8) and 0xFF).toByte(), pcm[i * 2 + 1])
        }
    }

    @Test
    fun `zero-length read appends nothing`() {
        val acc = PcmAccumulator()
        acc.append(ShortArray(4), 0, 0)
        assertEquals(0, acc.sampleCount)
        assertEquals(0, acc.pcmBytes().size)
    }

    @Test
    fun `toWav yields canonical header plus exact pcm`() {
        val acc = PcmAccumulator()
        acc.append(shortArrayOf(100, -100, 0, 12345), 0, 4)
        val wav = acc.toWav()
        assertEquals(WavWriter.HEADER_SIZE + 8, wav.size)
        assertEquals(4, acc.sampleCount)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun `invalid range rejected`() {
        val acc = PcmAccumulator()
        var rejected = false
        try {
            acc.append(ShortArray(4), 2, 4) // offset+count > size
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(0, acc.sampleCount)
    }
}
