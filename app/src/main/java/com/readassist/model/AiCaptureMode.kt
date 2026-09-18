package com.readassist.model

enum class AiCaptureMode(val storedValue: String) {
    TEXT_SELECTION("text_selection"),
    REGION_SCREENSHOT("region_screenshot");

    fun next(): AiCaptureMode = when (this) {
        TEXT_SELECTION -> REGION_SCREENSHOT
        REGION_SCREENSHOT -> TEXT_SELECTION
    }

    companion object {
        fun fromStoredValue(value: String): AiCaptureMode =
            values().firstOrNull { it.storedValue == value } ?: TEXT_SELECTION
    }
}
