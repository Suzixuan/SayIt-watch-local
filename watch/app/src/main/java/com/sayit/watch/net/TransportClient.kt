package com.sayit.watch.net

import com.sayit.watch.settings.DestinationValidator
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Debug-only HTTP sender for Delivery 1A. Posts raw WAV bytes to
 * `POST /api/watch/audio` with `Content-Type: audio/wav` and
 * `Authorization: Bearer <token>`. Only HTTP 201 Created counts as
 * transport success — never 202 or any other status.
 *
 * Release builds must never reach this code path (see [CleartextPolicy]
 * and the release-only factory guard in [Transport]).
 */
class TransportClient(
    private val cleartextAllowed: Boolean,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000,
) {

    sealed class UploadResult {
        data class Success(val requestId: String, val status: Int, val body: String) : UploadResult()
        data class Failure(val reason: String) : UploadResult()
    }

    /**
     * @return true only when the HTTP status maps to durable transport success.
     * 201 Created is success; 202 Accepted is explicitly not.
     */
    fun isTransportSuccess(status: Int): Boolean = status == 201

    fun upload(
        ip: String,
        port: Int,
        token: String,
        wav: ByteArray,
    ): UploadResult {
        if (!cleartextAllowed) {
            return UploadResult.Failure("cleartext HTTP is disabled in this build")
        }
        val validated = DestinationValidator.validate(ip, port.toString())
        if (validated !is DestinationValidator.ValidationResult.Valid) {
            val reason = (validated as DestinationValidator.ValidationResult.Invalid).reason
            return UploadResult.Failure("invalid destination: $reason")
        }
        if (token.length < 32) {
            return UploadResult.Failure("dev token must be at least 32 bytes")
        }
        val requestId = UUID.randomUUID().toString()
        val url = URL("http://$ip:$port/api/watch/audio")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-Request-Id", requestId)
            connection.setFixedLengthStreamingMode(wav.size)

            val out: OutputStream = connection.outputStream
            out.use { it.write(wav) }

            val status = connection.responseCode
            val bodyStream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = bodyStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (isTransportSuccess(status)) {
                UploadResult.Success(requestId, status, body)
            } else {
                UploadResult.Failure("HTTP $status (requestId=$requestId)")
            }
        } catch (e: Exception) {
            UploadResult.Failure("network error: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }
}
