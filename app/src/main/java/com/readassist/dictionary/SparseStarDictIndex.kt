package com.readassist.dictionary

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class StarDictRecord(
    val headword: String,
    val entryOrder: Long,
    val offset: Long,
    val size: Long
)

internal class SparseStarDictIndex private constructor(
    private val stride: Int,
    private val checkpoints: List<Checkpoint>
) {
    private data class Checkpoint(
        val headword: String,
        val entryOrder: Long,
        val byteOffset: Long
    )

    fun find(indexFile: File, offsetBits: Int, rawQuery: String, limit: Int): List<StarDictRecord> {
        if (limit <= 0 || checkpoints.isEmpty()) return emptyList()
        val query = normalize(rawQuery)
        if (query.isEmpty()) return emptyList()

        val exact = LinkedHashMap<String, StarDictRecord>()
        scanNear(indexFile, offsetBits, query) { record ->
            if (normalize(record.headword) == query) {
                exact.putIfAbsent(recordKey(record), record)
            }
        }
        if (exact.isNotEmpty()) return exact.values.take(limit)

        val prefix = LinkedHashMap<String, StarDictRecord>()
        scanNear(indexFile, offsetBits, query) { record ->
            if (normalize(record.headword).startsWith(query)) {
                prefix.putIfAbsent(recordKey(record), record)
            }
        }
        return prefix.values.sortedBy { normalize(it.headword) }.take(limit)
    }

    fun readByOrder(indexFile: File, offsetBits: Int, targetOrder: Long): StarDictRecord? {
        if (targetOrder < 0 || checkpoints.isEmpty()) return null
        val checkpoint = checkpoints.lastOrNull { it.entryOrder <= targetOrder } ?: checkpoints.first()
        RandomAccessFile(indexFile, "r").use { input ->
            input.seek(checkpoint.byteOffset)
            var order = checkpoint.entryOrder
            while (order <= targetOrder) {
                val record = readRecord(input, offsetBits, order) ?: return null
                if (order == targetOrder) return record
                order++
            }
        }
        return null
    }

    private fun scanNear(
        indexFile: File,
        offsetBits: Int,
        key: String,
        consume: (StarDictRecord) -> Unit
    ) {
        val insertion = checkpoints.binarySearch { it.headword.compareTo(key) }
            .let { if (it >= 0) it else -it - 1 }
        val checkpointIndex = (insertion - 2).coerceAtLeast(0)
        val checkpoint = checkpoints[checkpointIndex]
        val maxEntries = stride * 4
        RandomAccessFile(indexFile, "r").use { input ->
            input.seek(checkpoint.byteOffset)
            var order = checkpoint.entryOrder
            repeat(maxEntries) {
                val record = readRecord(input, offsetBits, order++) ?: return
                consume(record)
            }
        }
    }

    private fun recordKey(record: StarDictRecord): String =
        "${record.entryOrder}:${record.offset}:${record.size}"

    companion object {
        private const val MAGIC = 0x52415349 // RASI
        private const val VERSION = 1
        const val DEFAULT_STRIDE = 256

        fun build(
            indexFile: File,
            sparseFile: File,
            offsetBits: Int,
            expectedCount: Long,
            dictionaryDataLength: Long,
            stride: Int = DEFAULT_STRIDE
        ): SparseStarDictIndex {
            require(offsetBits == 32 || offsetBits == 64)
            require(stride > 0)
            val checkpoints = mutableListOf<Checkpoint>()
            BufferedInputStream(FileInputStream(indexFile), 64 * 1024).use { input ->
                var order = 0L
                var bytePosition = 0L
                var previousCheckpoint = ""
                while (true) {
                    val entryByteOffset = bytePosition
                    var value = input.read()
                    if (value == -1) break
                    bytePosition++

                    val isCheckpoint = order % stride == 0L
                    val wordBytes = if (isCheckpoint) ByteArrayOutputStream(32) else null
                    var wordLength = 0
                    while (value != 0) {
                        wordBytes?.write(value)
                        wordLength++
                        value = input.read()
                        if (value == -1) throw EOFException(".idx 词条缺少结尾零字节")
                        bytePosition++
                    }
                    require(wordLength > 0) { ".idx 包含空词条" }

                    val recordOffset = readUnsignedBigEndian(input, offsetBits / 8)
                    val recordSize = readUnsignedBigEndian(input, 4)
                    bytePosition += offsetBits / 8 + 4
                    require(recordOffset >= 0 && recordSize >= 0 && recordOffset + recordSize <= dictionaryDataLength) {
                        "第 $order 个词条的释义范围越界"
                    }

                    if (isCheckpoint) {
                        val normalized = normalize(wordBytes!!.toString(StandardCharsets.UTF_8.name()))
                        require(checkpoints.isEmpty() || previousCheckpoint <= normalized) {
                            ".idx 稀疏定位点未按忽略大小写的 StarDict 规则排序"
                        }
                        checkpoints += Checkpoint(normalized, order, entryByteOffset)
                        previousCheckpoint = normalized
                    }
                    order++
                }
                require(order == expectedCount) {
                    ".idx 词条数 $order 与 .ifo 声明 $expectedCount 不一致"
                }
            }
            require(checkpoints.isNotEmpty()) { ".idx 不能为空" }
            writeSparseFile(sparseFile, stride, checkpoints)
            return SparseStarDictIndex(stride, checkpoints)
        }

        fun load(sparseFile: File): SparseStarDictIndex {
            DataInputStream(BufferedInputStream(FileInputStream(sparseFile))).use { input ->
                require(input.readInt() == MAGIC) { "稀疏索引文件标识错误" }
                require(input.readInt() == VERSION) { "稀疏索引版本不支持" }
                val stride = input.readInt()
                require(stride > 0) { "稀疏索引步长无效" }
                val count = input.readInt()
                require(count > 0) { "稀疏索引不能为空" }
                val checkpoints = ArrayList<Checkpoint>(count)
                repeat(count) {
                    val entryOrder = input.readLong()
                    val byteOffset = input.readLong()
                    val length = input.readInt()
                    require(length in 1..1_048_576) { "稀疏索引词头长度无效" }
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    checkpoints += Checkpoint(String(bytes, StandardCharsets.UTF_8), entryOrder, byteOffset)
                }
                require(input.read() == -1) { "稀疏索引存在多余数据" }
                return SparseStarDictIndex(stride, checkpoints)
            }
        }

        private fun writeSparseFile(file: File, stride: Int, checkpoints: List<Checkpoint>) {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(stride)
                output.writeInt(checkpoints.size)
                checkpoints.forEach { checkpoint ->
                    val bytes = checkpoint.headword.toByteArray(StandardCharsets.UTF_8)
                    output.writeLong(checkpoint.entryOrder)
                    output.writeLong(checkpoint.byteOffset)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
        }

        private fun readRecord(
            input: RandomAccessFile,
            offsetBits: Int,
            entryOrder: Long
        ): StarDictRecord? {
            val first = input.read()
            if (first == -1) return null
            val wordBytes = ByteArrayOutputStream(32)
            var value = first
            while (value != 0) {
                wordBytes.write(value)
                value = input.read()
                if (value == -1) throw EOFException(".idx 词条缺少结尾零字节")
            }
            require(wordBytes.size() > 0) { ".idx 包含空词条" }
            return StarDictRecord(
                headword = wordBytes.toString(StandardCharsets.UTF_8.name()),
                entryOrder = entryOrder,
                offset = readUnsignedBigEndian(input, offsetBits / 8),
                size = readUnsignedBigEndian(input, 4)
            )
        }

        private fun readUnsignedBigEndian(input: RandomAccessFile, byteCount: Int): Long {
            var result = 0L
            repeat(byteCount) {
                val value = input.read()
                if (value == -1) throw EOFException(".idx 数值字段被截断")
                result = (result shl 8) or value.toLong()
            }
            return result
        }

        private fun readUnsignedBigEndian(input: InputStream, byteCount: Int): Long {
            var result = 0L
            repeat(byteCount) {
                val value = input.read()
                if (value == -1) throw EOFException(".idx 数值字段被截断")
                result = (result shl 8) or value.toLong()
            }
            return result
        }

        private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}
