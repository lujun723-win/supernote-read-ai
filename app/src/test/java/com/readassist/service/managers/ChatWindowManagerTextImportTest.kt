package com.readassist.service.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatWindowManagerTextImportTest {
    @Test
    fun `same selected text arriving twice is not duplicated`() {
        assertEquals("someone", mergeImportedText("someone", "someone"))
        assertEquals("someone\n", mergeImportedText("someone\n", " someone "))
    }

    @Test
    fun `different selected text is appended`() {
        assertEquals("first\nsecond", mergeImportedText("first", "second"))
    }
}
