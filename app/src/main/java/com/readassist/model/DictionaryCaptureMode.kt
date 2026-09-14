package com.readassist.model

enum class DictionaryCaptureMode(val storedValue: String) {
    REGION_OCR("region_ocr"),
    TEXT_SELECTION("text_selection");

    companion object {
        fun fromStoredValue(value: String): DictionaryCaptureMode =
            values().firstOrNull { it.storedValue == value } ?: REGION_OCR
    }
}
