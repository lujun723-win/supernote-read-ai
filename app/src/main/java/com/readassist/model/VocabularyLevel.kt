package com.readassist.model

enum class VocabularyLevel(val storedValue: String, val difficulty: Int) {
    PRIMARY("primary", 0),
    JUNIOR_HIGH("junior_high", 1),
    SENIOR_HIGH("senior_high", 2),
    CET4("cet4", 3),
    CET6("cet6", 4),
    POSTGRADUATE("postgraduate", 5),
    IELTS_TOEFL("ielts_toefl", 6);

    companion object {
        fun fromStoredValue(value: String): VocabularyLevel =
            values().firstOrNull { it.storedValue == value } ?: SENIOR_HIGH

        fun fromDifficulty(value: Int): VocabularyLevel =
            values().firstOrNull { it.difficulty == value } ?: IELTS_TOEFL
    }
}
