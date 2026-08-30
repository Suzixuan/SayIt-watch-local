package com.sayit.watch.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Z3 Repair 2 必修 3: the runtime controls must match the frozen candidate
 * previews and stay usable on the 480×480 round screen. These automated layout
 * constraints pin the [WatchUiMetrics] invariants; the previews in
 * `design/watch-ui/0.2.0-dev.2-candidate.2/previews` are generated from the
 * same numbers. Real round-screen touch/clip verification stays with the
 * later device gate.
 */
class WatchUiMetricsTest {

    @Test
    fun `wide chips meet the minimum 48 dp touch height`() {
        assertTrue(WatchUiMetrics.WideChipHeightDp >= 48.dp)
        assertTrue(WatchUiMetrics.RowMinHeightDp >= 48.dp)
    }

    @Test
    fun `main circular action keeps the Wear default 52 dp size`() {
        assertEquals(52.dp, WatchUiMetrics.MainActionSizeDp)
    }

    @Test
    fun `wide chip width stays inside the 480x480 circular safe area`() {
        val safeWidth = 480.dp - WatchUiMetrics.ScreenSidePaddingDp * 2
        assertTrue(WatchUiMetrics.WideChipWidthDp <= safeWidth)
        assertTrue(WatchUiMetrics.WideChipWidthDp > 0.dp)
    }

    @Test
    fun `side-by-side failure chips fit the circular safe area`() {
        val gap = 20.dp
        val combined = WatchUiMetrics.HalfChipWidthDp * 2 + gap
        val safeWidth = 480.dp - WatchUiMetrics.ScreenSidePaddingDp * 2
        assertTrue(combined <= safeWidth)
    }
}
