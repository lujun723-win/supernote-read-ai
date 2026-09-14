package com.readassist.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StarDictArchiveExtractorTest {
    @Test
    fun extractsMatchingStarDictFilesFromNestedFolder() {
        val target = Files.createTempDirectory("stardict-archive-").toFile()
        try {
            val extracted = StarDictArchiveExtractor.extract(
                archive(
                    "ecdict/ecdict.dict" to "definition".toByteArray(),
                    "ecdict/ecdict.idx" to byteArrayOf(1, 2, 3),
                    "ecdict/ecdict.ifo" to "bookname=ECDICT".toByteArray()
                ),
                target
            )

            assertEquals("ecdict", extracted.baseName)
            assertEquals("bookname=ECDICT", extracted.infoFile.readText())
            assertEquals(3, extracted.indexFile.length())
            assertEquals("definition", extracted.dictionaryFile.readText())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun rejectsMismatchedBaseNames() {
        val target = Files.createTempDirectory("stardict-archive-").toFile()
        try {
            val error = runCatching {
                StarDictArchiveExtractor.extract(
                    archive(
                        "ecdict.dict" to byteArrayOf(1),
                        "other.idx" to byteArrayOf(2),
                        "ecdict.ifo" to byteArrayOf(3)
                    ),
                    target
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("文件名不一致"))
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingComponent() {
        val target = Files.createTempDirectory("stardict-archive-").toFile()
        try {
            val error = runCatching {
                StarDictArchiveExtractor.extract(
                    archive(
                        "ecdict.idx" to byteArrayOf(1),
                        "ecdict.ifo" to byteArrayOf(2)
                    ),
                    target
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("缺少 .dict"))
        } finally {
            target.deleteRecursively()
        }
    }

    private fun archive(vararg files: Pair<String, ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            files.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
