package com.readassist.model

enum class OcrLanguage(val storedValue: String) {
    CHINESE("chinese"),
    LATIN("latin");

    companion object {
        fun fromStoredValue(value: String): OcrLanguage =
            values().firstOrNull { it.storedValue == value } ?: CHINESE
    }
}
