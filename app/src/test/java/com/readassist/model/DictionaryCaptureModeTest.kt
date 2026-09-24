package com.readassist.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryCaptureModeTest {
    @Test
    fun `stored values round trip`() {
        DictionaryCaptureMode.values().forEach { mode ->
            assertEquals(mode, DictionaryCaptureMode.fromStoredValue(mode.storedValue))
        }
    }

    @Test
    fun `unknown stored value defaults to region ocr`() {
        assertEquals(
            DictionaryCaptureMode.REGION_OCR,
            DictionaryCaptureMode.fromStoredValue("unknown")
        )
    }
}
