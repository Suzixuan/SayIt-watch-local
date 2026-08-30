package com.sayit.watch.recording

/**
 * Accumulates 16-bit little-endian PCM bytes exactly as returned by
 * AudioRecord.read. Only the samples actually read are appended — unused
 * buffer bytes are never written, which is what makes partial-read handling
 * exact and testable without a real microphone.
 */
class PcmAccumulator {

    private val buffer = java.io.ByteArrayOutputStream()

    /** Number of 16-bit samples captured so far. */
    var sampleCount: Int = 0
        private set

    /** Appends [readCount] samples from [samples], starting at [offset]. */
    fun append(samples: ShortArray, offset: Int, readCount: Int) {
        require(offset >= 0 && readCount >= 0 && offset + readCount <= samples.size) {
            "invalid read range"
        }
        val byteCount = readCount * 2
        val bytes = ByteArray(byteCount)
        for (i in 0 until readCount) {
            val sample = samples[offset + i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
        buffer.write(bytes)
        sampleCount += readCount
    }

    /** Raw PCM bytes captured so far (exactly readCount * 2 bytes per read). */
    fun pcmBytes(): ByteArray = buffer.toByteArray()

    /** Builds the complete canonical WAV from captured PCM. */
    fun toWav(): ByteArray = WavWriter.buildWav(pcmBytes(), buffer.size())

    fun reset() {
        buffer.reset()
        sampleCount = 0
    }
}
