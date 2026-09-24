package com.readassist.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyLevelTest {
    @Test
    fun `stored values round trip`() {
        VocabularyLevel.values().forEach { level ->
            assertEquals(level, VocabularyLevel.fromStoredValue(level.storedValue))
            assertEquals(level, VocabularyLevel.fromDifficulty(level.difficulty))
        }
    }

    @Test
    fun `unknown stored value uses senior high default`() {
        assertEquals(VocabularyLevel.SENIOR_HIGH, VocabularyLevel.fromStoredValue("unknown"))
    }
}
