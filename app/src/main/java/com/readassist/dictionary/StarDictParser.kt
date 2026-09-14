package com.readassist.dictionary

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

object StarDictParser {
    fun parseInfo(text: String): StarDictInfo {
        val normalized = text.removePrefix("\uFEFF")
        require(normalized.lineSequence().firstOrNull()?.trim() == "StarDict's dict ifo file") {
            "不是有效的 StarDict .ifo 文件"
        }
        val values = normalized.lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .associate { line ->
                val separator = line.indexOf('=')
                line.substring(0, separator) to line.substring(separator + 1)
            }

        val version = values["version"] ?: error(".ifo 缺少 version")
        require(version == "2.4.2" || version == "3.0.0") { "不支持的 StarDict 版本: $version" }
        val offsetBits = values["idxoffsetbits"]?.toIntOrNull() ?: 32
        require(offsetBits == 32 || offsetBits == 64) { "idxoffsetbits 只能是 32 或 64" }

        return StarDictInfo(
            bookName = values["bookname"]?.takeIf { it.isNotBlank() } ?: error(".ifo 缺少 bookname"),
            wordCount = values["wordcount"]?.toLongOrNull() ?: error(".ifo 缺少有效 wordcount"),
            indexFileSize = values["idxfilesize"]?.toLongOrNull() ?: error(".ifo 缺少有效 idxfilesize"),
            indexOffsetBits = offsetBits,
            sameTypeSequence = values["sametypesequence"].orEmpty(),
            synonymCount = values["synwordcount"]?.toLongOrNull() ?: 0L
        )
    }

    fun readIndex(input: InputStream, offsetBits: Int, onEntry: (StarDictIndexEntry) -> Unit): Long {
        require(offsetBits == 32 || offsetBits == 64)
        var count = 0L
        while (true) {
            val first = input.read()
            if (first == -1) break

            val wordBytes = ByteArrayOutputStream()
            var value = first
            while (value != 0) {
                wordBytes.write(value)
                value = input.read()
                if (value == -1) throw EOFException(".idx 词条缺少结尾零字节")
            }
            val headword = wordBytes.toString(StandardCharsets.UTF_8.name())
            require(headword.isNotBlank()) { ".idx 包含空词条" }

            val offset = readUnsignedBigEndian(input, offsetBits / 8)
            val size = readUnsignedBigEndian(input, 4)
            onEntry(StarDictIndexEntry(headword, offset, size))
            count++
        }
        return count
    }

    fun readSynonyms(input: InputStream, onSynonym: (String, Long) -> Unit): Long {
        var count = 0L
        while (true) {
            val first = input.read()
            if (first == -1) break
            val wordBytes = ByteArrayOutputStream()
            var value = first
            while (value != 0) {
                wordBytes.write(value)
                value = input.read()
                if (value == -1) throw EOFException(".syn 词条缺少结尾零字节")
            }
            val synonym = wordBytes.toString(StandardCharsets.UTF_8.name())
            require(synonym.isNotBlank()) { ".syn 包含空词条" }
            onSynonym(synonym, readUnsignedBigEndian(input, 4))
            count++
        }
        return count
    }

    fun parseDefinition(data: ByteArray, sameTypeSequence: String): String {
        val fields = if (sameTypeSequence.isNotEmpty()) {
            parseWithSequence(data, sameTypeSequence)
        } else {
            parseTypedFields(data)
        }
        return fields.filter { it.isNotBlank() }.joinToString("\n\n").trim()
    }

    private fun parseWithSequence(data: ByteArray, sequence: String): List<String> {
        val fields = mutableListOf<String>()
        var position = 0
        sequence.forEachIndexed { index, type ->
            if (position >= data.size) return@forEachIndexed
            val last = index == sequence.lastIndex
            if (type.isLowerCase()) {
                val end = if (last) data.size else findNull(data, position)
                fields += decodeText(data, position, end)
                position = if (last) data.size else end + 1
            } else {
                require(position + 4 <= data.size) { "词典二进制字段长度损坏" }
                val length = readUnsignedBigEndian(data, position, 4).toInt()
                position += 4
                require(length >= 0 && position + length <= data.size) { "词典二进制字段越界" }
                position += length
            }
        }
        return fields
    }

    private fun parseTypedFields(data: ByteArray): List<String> {
        val fields = mutableListOf<String>()
        var position = 0
        while (position < data.size) {
            val type = data[position++].toInt().toChar()
            if (type.isLowerCase()) {
                val end = findNull(data, position)
                fields += decodeText(data, position, end)
                position = if (end < data.size) end + 1 else data.size
            } else {
                require(position + 4 <= data.size) { "词典二进制字段长度损坏" }
                val length = readUnsignedBigEndian(data, position, 4).toInt()
                position += 4
                require(length >= 0 && position + length <= data.size) { "词典二进制字段越界" }
                position += length
            }
        }
        return fields
    }

    private fun findNull(data: ByteArray, start: Int): Int {
        for (index in start until data.size) {
            if (data[index].toInt() == 0) return index
        }
        return data.size
    }

    private fun decodeText(data: ByteArray, start: Int, end: Int): String =
        String(data, start, end - start, StandardCharsets.UTF_8).trim()

    private fun readUnsignedBigEndian(input: InputStream, byteCount: Int): Long {
        var result = 0L
        repeat(byteCount) {
            val value = input.read()
            if (value == -1) throw EOFException(".idx 数值字段被截断")
            result = (result shl 8) or value.toLong()
        }
        return result
    }

    private fun readUnsignedBigEndian(data: ByteArray, start: Int, byteCount: Int): Long {
        var result = 0L
        repeat(byteCount) { index ->
            result = (result shl 8) or (data[start + index].toLong() and 0xff)
        }
        return result
    }
}
