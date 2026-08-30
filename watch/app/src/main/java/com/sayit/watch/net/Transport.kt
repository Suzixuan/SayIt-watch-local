package com.sayit.watch.net

import com.sayit.watch.BuildConfig

/**
 * Single gateway to the HTTP sender. Debug builds expose a working
 * [TransportClient]; release builds expose none (null) so there is no usable
 * HTTP sender in release runtime code.
 */
object Transport {

    /** @return the debug sender, or null in release builds. */
    fun sender(): TransportClient? =
        if (BuildConfig.DEBUG) TransportClient(cleartextAllowed = true) else null

    /** @return true only in debug builds. */
    fun isDebug(): Boolean = BuildConfig.DEBUG
}
