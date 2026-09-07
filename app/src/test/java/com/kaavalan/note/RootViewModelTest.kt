package com.kaavalan.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android ingress events are intentionally owned by the activity-scoped
 * RootViewModel. These tests lock the one-shot contract used by the share
 * receiver, capture widget, and quick-settings tile.
 */
class RootViewModelTest {

    @Test
    fun sharedText_isAvailableUntilHomeConsumesIt() {
        val viewModel = RootViewModel()

        viewModel.onSharedText("Ask SI Meena for the station diary")
        assertEquals("Ask SI Meena for the station diary", viewModel.sharedText.value)

        viewModel.consumeSharedText()
        assertNull(viewModel.sharedText.value)
    }

    @Test
    fun quickCapture_isOneShot() {
        val viewModel = RootViewModel()

        assertFalse(viewModel.quickCapture.value)
        viewModel.onQuickCapture()
        assertTrue(viewModel.quickCapture.value)

        viewModel.consumeQuickCapture()
        assertFalse(viewModel.quickCapture.value)
    }
}
