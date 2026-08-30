package com.sayit.watch.settings

/**
 * Frozen Dev Token representation for Delivery 1A: exactly 64 hexadecimal
 * characters (32 decoded bytes = 256 bits of material). Surrounding
 * whitespace is trimmed before validation; any other format is rejected.
 *
 * The identical rule is enforced on the Windows receiver side
 * (watch_receiver::config::validate_token) and by the UI gating.
 */
object DevTokenValidator {

    private val hex64 = Regex("^[0-9a-fA-F]{64}$")

    /** @return true when [token] trims to exactly 64 hex characters. */
    fun isValid(token: String?): Boolean {
        val trimmed = token?.trim().orEmpty()
        return hex64.matches(trimmed)
    }

    /** @return the canonical trimmed token, or null when invalid. */
    fun canonicalOrNull(token: String?): String? {
        val trimmed = token?.trim().orEmpty()
        return if (hex64.matches(trimmed)) trimmed else null
    }
}
