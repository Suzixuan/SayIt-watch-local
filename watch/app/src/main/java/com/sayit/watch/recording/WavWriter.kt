package com.sayit.watch.recording

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Builds a canonical little-endian PCM RIFF/WAV file from captured 16-bit mono
 * samples. Duration is derived strictly from the successfully captured sample
 * count, never from wall-clock time.
 */
object WavWriter {

    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    const val BYTE_RATE = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
    const val BLOCK_ALIGN = CHANNELS * BYTES_PER_SAMPLE

    /** Size in bytes of the canonical 44-byte RIFF/WAV header. */
    const val HEADER_SIZE = 44

    /** Duration in milliseconds for a given captured sample count. */
    fun durationMs(sampleCount: Int): Long =
        if (sampleCount <= 0) 0L else (sampleCount.toLong() * 1000L) / SAMPLE_RATE

    /**
     * Writes the canonical RIFF/WAV header for [dataSize] payload bytes.
     * The caller is responsible for writing exactly [dataSize] bytes after this.
     */
    fun writeHeader(out: OutputStream, dataSize: Int) {
        val byteRate = BYTE_RATE
        val blockAlign = BLOCK_ALIGN
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intLe(HEADER_SIZE - 8 + dataSize)) // chunk size = 36 + data
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(intLe(16)) // fmt chunk size
        out.write(shortLe(1)) // PCM format
        out.write(shortLe(CHANNELS))
        out.write(intLe(SAMPLE_RATE))
        out.write(intLe(byteRate))
        out.write(shortLe(blockAlign))
        out.write(shortLe(BITS_PER_SAMPLE))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(intLe(dataSize))
    }

    /**
     * Builds a complete in-memory WAV from raw little-endian 16-bit PCM samples
     * (exactly the bytes captured). Returns the full RIFF/WAV bytes.
     */
    fun buildWav(pcmBytes: ByteArray, pcmLength: Int): ByteArray {
        require(pcmLength >= 0 && pcmLength <= pcmBytes.size) { "invalid pcm length" }
        require(pcmLength % 2 == 0) { "PCM payload must be even (16-bit samples)" }
        val out = ByteArrayOutputStream(HEADER_SIZE + pcmLength)
        writeHeader(out, pcmLength)
        out.write(pcmBytes, 0, pcmLength)
        return out.toByteArray()
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun shortLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )
}
