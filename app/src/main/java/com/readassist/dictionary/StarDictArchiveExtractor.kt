package com.readassist.dictionary

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class ExtractedStarDictArchive(
    val baseName: String,
    val infoFile: File,
    val indexFile: File,
    val dictionaryFile: File
)

internal object StarDictArchiveExtractor {
    fun extract(input: InputStream, targetDirectory: File): ExtractedStarDictArchive {
        require(targetDirectory.isDirectory || targetDirectory.mkdirs()) { "无法创建词典临时目录" }

        val extracted = mutableMapOf<Component, Pair<String, File>>()
        ZipInputStream(BufferedInputStream(input)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/')
                    val component = Component.fromFileName(fileName)
                    if (component != null) {
                        require(component !in extracted) { "词典压缩包内包含多个 ${component.suffix} 文件" }
                        val output = File(targetDirectory, component.targetName)
                        BufferedOutputStream(FileOutputStream(output)).use { archive.copyTo(it) }
                        extracted[component] = fileName to output
                    }
                }
                archive.closeEntry()
            }
        }

        val missing = Component.values().filterNot(extracted::containsKey)
        require(missing.isEmpty()) {
            "词典压缩包缺少 ${missing.joinToString { it.suffix }} 文件"
        }
        val baseNames = Component.values().map { component ->
            extracted.getValue(component).first.dropLast(component.suffix.length)
        }
        require(baseNames.distinctBy { it.lowercase(Locale.ROOT) }.size == 1) {
            "词典压缩包内的 .ifo、.idx、.dict 文件名不一致"
        }

        return ExtractedStarDictArchive(
            baseName = baseNames.first(),
            infoFile = extracted.getValue(Component.INFO).second,
            indexFile = extracted.getValue(Component.INDEX).second,
            dictionaryFile = extracted.getValue(Component.DICTIONARY).second
        )
    }

    private enum class Component(val suffix: String, val targetName: String) {
        INFO(".ifo", OfflineDictionaryManager.INFO_FILE),
        INDEX(".idx", OfflineDictionaryManager.INDEX_FILE),
        DICTIONARY(".dict", OfflineDictionaryManager.DICT_FILE);

        companion object {
            fun fromFileName(fileName: String): Component? {
                val lower = fileName.lowercase(Locale.ROOT)
                return values().firstOrNull { lower.endsWith(it.suffix) }
            }
        }
    }
}
