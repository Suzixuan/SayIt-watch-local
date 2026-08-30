package com.sayit.watch.recording

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Captures 16 kHz / 16-bit / mono audio through [AudioRecord] on a dedicated
 * I/O thread. The 16 kHz capability is verified up front: if any of the
 * required checks fail the capture fails visibly with "16 kHz unsupported"
 * and never resamples or falls back to another sample rate.
 */
class AudioCapture {

    sealed class InitResult {
        data class Ready(val minBufferSize: Int, val state: Int, val sampleRate: Int) : InitResult()
        data class Failed(val reason: String) : InitResult()
    }

    /** Verifies native 16 kHz support without starting capture. */
    fun verifySupported(): InitResult {
        val minBuffer = try {
            AudioRecord.getMinBufferSize(
                WavWriter.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (e: IllegalArgumentException) {
            return InitResult.Failed("16 kHz unsupported: ${e.message}")
        }
        if (minBuffer <= 0) {
            return InitResult.Failed("16 kHz unsupported: minBufferSize=$minBuffer")
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WavWriter.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer,
            )
        } catch (e: Exception) {
            return InitResult.Failed("16 kHz unsupported: ${e.message}")
        }
        return try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                InitResult.Failed("16 kHz unsupported: state=${record.state}")
            } else {
                val actualRate = record.sampleRate
                if (actualRate != WavWriter.SAMPLE_RATE) {
                    InitResult.Failed("16 kHz unsupported: actual sampleRate=$actualRate")
                } else {
                    InitResult.Ready(minBuffer, record.state, actualRate)
                }
            }
        } finally {
            runCatching { record.release() }
        }
    }

    /**
     * Reads audio until [isActive] returns false (user Stop) or a maximum
     * duration of [maxDurationMs] elapses, writing exactly the bytes returned
     * by each AudioRecord.read call into [pcmSink] — unused buffer bytes are
     * never written. Returns the total captured sample count.
     *
     * @throws AudioCaptureException on initialization failure.
     */
    fun record(
        maxDurationMs: Int,
        isActive: () -> Boolean,
        pcmSink: (ByteArray, Int) -> Unit,
    ): Int {
        val minBuffer = AudioRecord.getMinBufferSize(
            WavWriter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw AudioCaptureException("16 kHz unsupported: minBufferSize=$minBuffer")

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            WavWriter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioCaptureException("16 kHz unsupported: state=${record.state}")
        }
        if (record.sampleRate != WavWriter.SAMPLE_RATE) {
            record.release()
            throw AudioCaptureException("16 kHz unsupported: actual sampleRate=${record.sampleRate}")
        }

        val buffer = ShortArray(minBuffer / 2)
        var capturedSamples = 0
        val startedAt = System.currentTimeMillis()
        try {
            record.startRecording()
            while (isActive()) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= maxDurationMs) break
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    // Wait briefly and retry; a transient zero is not a capture end.
                    Thread.sleep(20)
                    continue
                }
                val byteCount = read * 2
                val bytes = ByteArray(byteCount)
                for (i in 0 until read) {
                    val sample = buffer[i].toInt()
                    bytes[i * 2] = (sample and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
                }
                pcmSink(bytes, byteCount)
                capturedSamples += read
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        return capturedSamples
    }
}

class AudioCaptureException(message: String) : Exception(message)
