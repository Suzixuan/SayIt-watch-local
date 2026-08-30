package com.sayit.watch.settings

/**
 * Validates the debug receiver destination. Delivery 1A accepts exactly one
 * RFC1918 private IPv4 (10/8, 172.16/12, 192.168/16) plus a port in 1..65535.
 * Hostnames, loopback, wildcard, link-local, IPv6, and public addresses are rejected.
 */
object DestinationValidator {

    sealed class ValidationResult {
        data class Valid(val ip: String, val port: Int) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun validate(ip: String?, portText: String?): ValidationResult {
        val rawIp = ip?.trim().orEmpty()
        if (rawIp.isEmpty()) return ValidationResult.Invalid("IP address is required")
        val match = ipv4.matchEntire(rawIp) ?: return ValidationResult.Invalid("must be a dotted IPv4 address")
        val octets = match.groupValues.drop(1).map { it.toInt() }
        if (octets.any { it > 255 }) return ValidationResult.Invalid("octet out of range 0-255")
        val parsedPort = portText?.trim()?.toIntOrNull()
            ?: return ValidationResult.Invalid("port must be an integer")
        if (parsedPort !in 1..65535) return ValidationResult.Invalid("port must be in 1-65535")
        val ip = octets.joinToString(".")
        return when {
            isLoopback(octets) -> ValidationResult.Invalid("loopback is not allowed")
            isLinkLocal(octets) -> ValidationResult.Invalid("link-local is not allowed")
            isWildcard(octets) -> ValidationResult.Invalid("wildcard 0.0.0.0 is not allowed")
            !isRfc1918(octets) -> ValidationResult.Invalid("must be an RFC1918 private IPv4 address")
            else -> ValidationResult.Valid(ip, parsedPort)
        }
    }

    /** @return true when the host part is any RFC1918 private block. */
    fun isRfc1918(o: List<Int>): Boolean = when {
        o[0] == 10 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        else -> false
    }

    private fun isLoopback(o: List<Int>) = o[0] == 127

    private fun isLinkLocal(o: List<Int>) = o[0] == 169 && o[1] == 254

    private fun isWildcard(o: List<Int>) = o.all { it == 0 }
}
