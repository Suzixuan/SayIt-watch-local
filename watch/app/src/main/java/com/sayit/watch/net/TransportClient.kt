package com.sayit.watch.net

import com.sayit.watch.settings.DestinationValidator
import com.sayit.watch.settings.DevTokenValidator
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Debug-only HTTP sender for Delivery 1A. Posts raw WAV bytes to
 * `POST /api/watch/audio` with `Content-Type: audio/wav`,
 * `Authorization: Bearer <token>` and a random request UUID in
 * `X-Request-Id`. Only HTTP 201 Created counts as transport success.
 *
 * The request UUID is preserved end to end: the receiver must echo the same
 * UUID as `requestId` in the 201 JSON, and [upload] verifies that echoed value
 * against the ID it sent. A missing or mismatched request ID is a failure.
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

    /**
     * Parses the 201 JSON body and verifies that the echoed `requestId` equals
     * the request UUID we sent. Success only when all three hold: status 201,
     * body parses, echoed requestId matches.
     */
    fun verifySuccessResponse(sentRequestId: String, status: Int, body: String): UploadResult {
        if (!isTransportSuccess(status)) {
            return UploadResult.Failure("HTTP $status (requestId=$sentRequestId)")
        }
        val echoed = extractRequestId(body)
            ?: return UploadResult.Failure("missing requestId in response (sent=$sentRequestId)")
        if (echoed != sentRequestId) {
            return UploadResult.Failure("requestId mismatch: sent=$sentRequestId echoed=$echoed")
        }
        return UploadResult.Success(echoed, status, body)
    }

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
        val canonicalToken = DevTokenValidator.canonicalOrNull(token)
            ?: return UploadResult.Failure("dev token must be exactly 64 hex characters")
        val requestId = UUID.randomUUID().toString()
        val url = URL("http://$ip:$port/api/watch/audio")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.setRequestProperty("Authorization", "Bearer $canonicalToken")
            connection.setRequestProperty("X-Request-Id", requestId)
            connection.setFixedLengthStreamingMode(wav.size)

            val out: OutputStream = connection.outputStream
            out.use { it.write(wav) }

            val status = connection.responseCode
            val bodyStream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = bodyStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            verifySuccessResponse(requestId, status, body)
        } catch (e: Exception) {
            UploadResult.Failure("network error: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRequestId(body: String): String? {
        // Minimal, dependency-free extraction of "requestId":"<value>" from the
        // receiver's JSON body. The receiver emits exactly one requestId field.
        val pattern = Regex(""""requestId"\s*:\s*"([^"]+)"""")
        return pattern.find(body)?.groupValues?.get(1)
    }
}
