package com.sayit.watch.net

/**
 * Cleartext transport policy. Debug builds allow cleartext HTTP to the LAN
 * debug receiver; release builds deny it. The release runtime must never
 * construct a usable HTTP sender — [Transport.create] is the only entry point
 * and it refuses to build one when cleartext is denied.
 */
object CleartextPolicy {

    /** True when cleartext HTTP is permitted for this build type. */
    fun allowsCleartext(isDebugBuild: Boolean): Boolean = isDebugBuild

    /** Factory used by the app. In release this always returns null. */
    fun createSender(cleartextAllowed: Boolean): TransportClient? =
        if (cleartextAllowed) TransportClient(cleartextAllowed = true) else null
}
