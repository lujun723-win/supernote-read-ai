package com.readassist.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AiCaptureModeTest {
    @Test
    fun `capture mode toggles and restores stored value`() {
        assertEquals(AiCaptureMode.REGION_SCREENSHOT, AiCaptureMode.TEXT_SELECTION.next())
        assertEquals(AiCaptureMode.TEXT_SELECTION, AiCaptureMode.REGION_SCREENSHOT.next())
        assertEquals(
            AiCaptureMode.REGION_SCREENSHOT,
            AiCaptureMode.fromStoredValue("region_screenshot")
        )
    }

    @Test
    fun `unknown stored value defaults to text selection`() {
        assertEquals(AiCaptureMode.TEXT_SELECTION, AiCaptureMode.fromStoredValue("unknown"))
    }
}
