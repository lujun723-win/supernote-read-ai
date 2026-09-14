package com.readassist.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class StarDictParserTest {
    @Test
    fun parsesRequiredInfoAnd64BitOffsetFlag() {
        val info = StarDictParser.parseInfo(
            """StarDict's dict ifo file
                version=3.0.0
                bookname=Test Dictionary
                wordcount=2
                idxfilesize=37
                idxoffsetbits=64
                sametypesequence=m
            """.trimIndent()
        )

        assertEquals("Test Dictionary", info.bookName)
        assertEquals(2, info.wordCount)
        assertEquals(64, info.indexOffsetBits)
        assertEquals("m", info.sameTypeSequence)
    }

    @Test
    fun parses32BitIndexEntries() {
        val bytes = ByteArrayOutputStream().apply {
            write("apple".toByteArray())
            write(0)
            write(longBytes(7, 4))
            write(longBytes(12, 4))
            write("中文".toByteArray())
            write(0)
            write(longBytes(19, 4))
            write(longBytes(6, 4))
        }.toByteArray()
        val entries = mutableListOf<StarDictIndexEntry>()

        val count = StarDictParser.readIndex(ByteArrayInputStream(bytes), 32, entries::add)

        assertEquals(2, count)
        assertEquals(StarDictIndexEntry("apple", 7, 12), entries[0])
        assertEquals(StarDictIndexEntry("中文", 19, 6), entries[1])
    }

    @Test
    fun parsesTextSequenceAndSkipsBinaryResource() {
        val bytes = ByteArrayOutputStream().apply {
            write("第一段".toByteArray())
            write(0)
            write(longBytes(3, 4))
            write(byteArrayOf(1, 2, 3))
            write("second".toByteArray())
        }.toByteArray()

        assertEquals("第一段\n\nsecond", StarDictParser.parseDefinition(bytes, "mWt"))
    }

    @Test
    fun rejectsTruncatedIndex() {
        val bytes = "broken".toByteArray()

        assertThrows(Exception::class.java) {
            StarDictParser.readIndex(ByteArrayInputStream(bytes), 32) { }
        }
    }

    @Test
    fun parsesSynonymTargetOrder() {
        val bytes = ByteArrayOutputStream().apply {
            write("colour".toByteArray())
            write(0)
            write(longBytes(3, 4))
        }.toByteArray()
        val synonyms = mutableListOf<Pair<String, Long>>()

        val count = StarDictParser.readSynonyms(ByteArrayInputStream(bytes)) { word, target ->
            synonyms += word to target
        }

        assertEquals(1, count)
        assertEquals("colour" to 3L, synonyms.single())
    }

    private fun longBytes(value: Long, count: Int): ByteArray =
        ByteArray(count) { index -> ((value ushr ((count - index - 1) * 8)) and 0xff).toByte() }
}
