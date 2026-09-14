package com.readassist.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class SparseStarDictIndexTest {
    @Test
    fun buildsLoadsAndFindsExactAndPrefixRecords() {
        withTemporaryDirectory { directory ->
            val indexFile = File(directory, "test.idx")
            val sparseFile = File(directory, "test.sparse")
            val entries = listOf(
                StarDictIndexEntry("apple", 0, 5),
                StarDictIndexEntry("application", 5, 7),
                StarDictIndexEntry("banana", 12, 3),
                StarDictIndexEntry("zebra", 15, 4)
            )
            indexFile.writeBytes(indexBytes(entries))

            SparseStarDictIndex.build(indexFile, sparseFile, 32, 4, 19, stride = 2)
            val index = SparseStarDictIndex.load(sparseFile)

            assertEquals(listOf("apple"), index.find(indexFile, 32, "APPLE", 10).map { it.headword })
            assertEquals(
                listOf("apple", "application"),
                index.find(indexFile, 32, "app", 10).map { it.headword }
            )
            assertEquals("banana", index.readByOrder(indexFile, 32, 2)?.headword)
        }
    }

    @Test
    fun rejectsDeclaredWordCountMismatch() {
        withTemporaryDirectory { directory ->
            val indexFile = File(directory, "test.idx").apply {
                writeBytes(indexBytes(listOf(StarDictIndexEntry("apple", 0, 5))))
            }

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                SparseStarDictIndex.build(indexFile, File(directory, "test.sparse"), 32, 2, 5)
            }
        }
    }

    private fun indexBytes(entries: List<StarDictIndexEntry>): ByteArray =
        ByteArrayOutputStream().apply {
            entries.forEach { entry ->
                write(entry.headword.toByteArray())
                write(0)
                write(longBytes(entry.offset, 4))
                write(longBytes(entry.size, 4))
            }
        }.toByteArray()

    private fun longBytes(value: Long, count: Int): ByteArray =
        ByteArray(count) { index -> ((value ushr ((count - index - 1) * 8)) and 0xff).toByte() }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("sparse-stardict-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
