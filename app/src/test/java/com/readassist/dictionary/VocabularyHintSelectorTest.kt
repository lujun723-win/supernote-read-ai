package com.readassist.dictionary

import com.readassist.model.VocabularyLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyHintSelectorTest {
    private val records = listOf(
        record("someone", VocabularyLevel.JUNIOR_HIGH, 419, "有人"),
        record("playoff", VocabularyLevel.SENIOR_HIGH, 3631, "季后赛"),
        record("franchise", VocabularyLevel.SENIOR_HIGH, 4357, "特许经营权"),
        record("respective", VocabularyLevel.CET4, 5015, "各自的")
    ).associateBy { it.word }

    private val selector = VocabularyHintSelector(VocabularyLookup(records::get), maxHints = 3)

    @Test
    fun `keeps selected level and harder words`() {
        val hints = selector.select(
            listOf(
                word("someone", 10),
                word("playoff", 20),
                word("respective", 30)
            ),
            VocabularyLevel.SENIOR_HIGH
        )

        assertEquals(listOf("playoff", "respective"), hints.map { it.word })
    }

    @Test
    fun `normalizes punctuation and only annotates first occurrence`() {
        val hints = selector.select(
            listOf(
                word("‘Franchise’", 10),
                word("franchise", 20)
            ),
            VocabularyLevel.PRIMARY
        )

        assertEquals(1, hints.size)
        assertEquals(10, hints.single().top)
    }

    @Test
    fun `limits page to hardest records`() {
        val hints = selector.select(
            listOf(
                word("someone", 10),
                word("playoff", 20),
                word("franchise", 30),
                word("respective", 40)
            ),
            VocabularyLevel.PRIMARY
        )

        assertEquals(3, hints.size)
        assertTrue("someone" !in hints.map { it.word })
    }

    @Test
    fun `default page limit is twenty hints`() {
        val words = (0 until 22).map { index -> "term${('a'.code + index).toChar()}" }
        val lookup = words.mapIndexed { index, value ->
            record(value, VocabularyLevel.CET4, 1_000 + index, "释义")
        }.associateBy { it.word }

        val hints = VocabularyHintSelector(VocabularyLookup(lookup::get)).select(
            words.mapIndexed { index, value -> word(value, index * 10) },
            VocabularyLevel.PRIMARY
        )

        assertEquals(20, hints.size)
    }

    private fun record(word: String, level: VocabularyLevel, rank: Int, gloss: String) =
        VocabularyRecord(word, level, rank, gloss)

    private fun word(text: String, top: Int) = RecognizedWord(text, 0, top, 20, top + 10)
}
