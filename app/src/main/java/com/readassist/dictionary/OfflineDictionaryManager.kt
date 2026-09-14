package com.readassist.dictionary

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

class OfflineDictionaryManager(private val context: Context) {
    private data class InstalledDictionary(
        val dictionary: OfflineDictionary,
        val directory: File,
        val indexOffsetBits: Int,
        val sameTypeSequence: String,
        val hasSynonyms: Boolean
    ) {
        val indexFile: File get() = File(directory, INDEX_FILE)
        val dictionaryFile: File get() = File(directory, DICT_FILE)
        val sparseFile: File get() = File(directory, SPARSE_FILE)
    }

    private val rootDirectory = File(context.filesDir, "offline_dictionaries")
    private val importing = AtomicBoolean(false)
    private val sparseCache = ConcurrentHashMap<String, SparseStarDictIndex>()

    init {
        rootDirectory.mkdirs()
        removeLegacySqliteIndex()
        removeIncompleteImports()
    }

    val isImporting: Boolean
        get() = importing.get()

    fun listDictionaries(): List<OfflineDictionary> =
        installedDictionaries().map { it.dictionary }.sortedBy { it.name.lowercase(Locale.ROOT) }

    fun hasBundledEcdict(): Boolean =
        context.assets.list("")?.any { it == BUNDLED_ECDICT_ASSET } == true

    fun installBundledEcdict(): DictionaryImportResult {
        check(hasBundledEcdict()) { "当前 APK 不包含内置 ECDICT" }
        check(importing.compareAndSet(false, true)) { "已有词典正在导入" }
        try {
            val finalDirectory = File(rootDirectory, BUNDLED_ECDICT_ID)
            if (finalDirectory.isDirectory) {
                return DictionaryImportResult(readInstalledDictionary(finalDirectory).dictionary)
            }

            val staging = File(rootDirectory, ".$BUNDLED_ECDICT_ID.tmp")
            require(staging.mkdir()) { "无法创建词典临时目录" }
            try {
                context.assets.open(BUNDLED_ECDICT_ASSET).use { input ->
                    StarDictArchiveExtractor.extract(input, staging)
                }
                val infoText = File(staging, INFO_FILE).readText(Charsets.UTF_8)
                val info = StarDictParser.parseInfo(infoText)
                validateInfo(info, hasSynonymFile = false)

                val sameDictionary = installedDictionaries().firstOrNull {
                    it.dictionary.name == info.bookName && it.dictionary.wordCount == info.wordCount
                }
                if (sameDictionary != null) {
                    return DictionaryImportResult(sameDictionary.dictionary)
                }
                return finalizeImport(staging, BUNDLED_ECDICT_ID, info)
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        } finally {
            importing.set(false)
        }
    }

    fun importStarDictFolder(folderUri: Uri): DictionaryImportResult {
        check(importing.compareAndSet(false, true)) { "已有词典正在导入" }
        try {
            return importStarDictFolderInternal(folderUri)
        } finally {
            importing.set(false)
        }
    }

    private fun importStarDictFolderInternal(folderUri: Uri): DictionaryImportResult {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("无法读取所选目录")
        require(folder.isDirectory) { "请选择 StarDict 文件夹" }

        val files = folder.listFiles().filter { it.isFile && !it.name.isNullOrBlank() }
        val ifoFiles = files.filter { it.name!!.lowercase(Locale.ROOT).endsWith(".ifo") }
        require(ifoFiles.size == 1) { "文件夹内必须且只能有一个 .ifo 文件" }
        val ifo = ifoFiles.single()
        val baseName = ifo.name!!.dropLast(4)
        val idx = findNamed(files, "$baseName.idx") ?: findNamed(files, "$baseName.idx.gz")
            ?: error("缺少 $baseName.idx 或 $baseName.idx.gz")
        val dict = findNamed(files, "$baseName.dict") ?: findNamed(files, "$baseName.dict.dz")
            ?: error("缺少 $baseName.dict 或 $baseName.dict.dz")
        val synonyms = findNamed(files, "$baseName.syn")

        val infoText = context.contentResolver.openInputStream(ifo.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("无法读取 ${ifo.name}")
        val info = StarDictParser.parseInfo(infoText)
        validateInfo(info, synonyms != null, baseName)

        val dictionaryId = UUID.randomUUID().toString()
        val staging = File(rootDirectory, ".$dictionaryId.tmp").apply { mkdirs() }
        try {
            File(staging, INFO_FILE).writeText(infoText, Charsets.UTF_8)
            val idxFile = File(staging, INDEX_FILE)
            copyDocument(idx, idxFile, idx.name!!.endsWith(".gz", ignoreCase = true))
            require(idxFile.length() == info.indexFileSize) {
                ".idx 大小与 .ifo 的 idxfilesize 不一致"
            }
            val dictFile = File(staging, DICT_FILE)
            copyDocument(dict, dictFile, dict.name!!.endsWith(".dz", ignoreCase = true))
            return finalizeImport(staging, dictionaryId, info)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun lookup(rawQuery: String): List<DictionaryDefinition> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()
        val results = mutableListOf<DictionaryDefinition>()
        for (installed in installedDictionaries()) {
            if (results.size >= LOOKUP_LIMIT) break
            val sparse = sparseCache.getOrPut(installed.dictionary.id) {
                SparseStarDictIndex.load(installed.sparseFile)
            }
            val records = sparse.find(
                indexFile = installed.indexFile,
                offsetBits = installed.indexOffsetBits,
                rawQuery = query,
                limit = LOOKUP_LIMIT - results.size
            )
            records.forEach { record ->
                val bytes = readRecord(installed.dictionaryFile, record.offset, record.size)
                val definition = StarDictParser.parseDefinition(bytes, installed.sameTypeSequence)
                if (definition.isNotBlank()) {
                    results += DictionaryDefinition(
                        installed.dictionary.name,
                        record.headword,
                        definition
                    )
                }
            }
        }
        return results
    }

    fun deleteAll() {
        sparseCache.clear()
        rootDirectory.listFiles()?.forEach { it.deleteRecursively() }
        removeLegacySqliteIndex()
    }

    private fun installedDictionaries(): List<InstalledDictionary> =
        rootDirectory.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map(::readInstalledDictionary)
            .orEmpty()

    private fun readInstalledDictionary(directory: File): InstalledDictionary {
        val properties = Properties().apply {
            FileInputStream(File(directory, METADATA_FILE)).use(::load)
        }
        require(properties.getProperty("formatVersion") == FORMAT_VERSION.toString()) {
            "词典 ${directory.name} 的存储版本不支持"
        }
        val id = properties.getProperty("id") ?: error("词典元数据缺少 id")
        require(id == directory.name) { "词典目录与 id 不一致" }
        val installed = InstalledDictionary(
            dictionary = OfflineDictionary(
                id = id,
                name = properties.getProperty("name") ?: error("词典元数据缺少 name"),
                wordCount = properties.getProperty("wordCount")?.toLongOrNull()
                    ?: error("词典元数据缺少 wordCount")
            ),
            directory = directory,
            indexOffsetBits = properties.getProperty("indexOffsetBits")?.toIntOrNull()
                ?: error("词典元数据缺少 indexOffsetBits"),
            sameTypeSequence = properties.getProperty("sameTypeSequence").orEmpty(),
            hasSynonyms = properties.getProperty("hasSynonyms").toBoolean()
        )
        require(!installed.hasSynonyms) { "当前原生直读版本不支持 .syn" }
        require(installed.indexFile.isFile && installed.dictionaryFile.isFile && installed.sparseFile.isFile) {
            "词典 ${installed.dictionary.name} 文件不完整"
        }
        return installed
    }

    private fun writeMetadata(directory: File, dictionary: OfflineDictionary, info: StarDictInfo) {
        val properties = Properties().apply {
            setProperty("formatVersion", FORMAT_VERSION.toString())
            setProperty("id", dictionary.id)
            setProperty("name", dictionary.name)
            setProperty("wordCount", dictionary.wordCount.toString())
            setProperty("indexOffsetBits", info.indexOffsetBits.toString())
            setProperty("sameTypeSequence", info.sameTypeSequence)
            setProperty("hasSynonyms", (info.synonymCount > 0).toString())
        }
        FileOutputStream(File(directory, METADATA_FILE)).use { properties.store(it, null) }
    }

    private fun validateInfo(info: StarDictInfo, hasSynonymFile: Boolean, baseName: String = "词典") {
        require(info.wordCount in 1..10_000_000) { "wordcount 超出支持范围" }
        require(info.synonymCount == 0L || hasSynonymFile) { ".ifo 声明了同义词，但缺少 $baseName.syn" }
        require(info.synonymCount == 0L) { "当前原生直读版本暂不支持含 .syn 的 StarDict 词典" }
    }

    private fun finalizeImport(
        staging: File,
        dictionaryId: String,
        info: StarDictInfo
    ): DictionaryImportResult {
        val idxFile = File(staging, INDEX_FILE)
        val dictFile = File(staging, DICT_FILE)
        require(idxFile.length() == info.indexFileSize) {
            ".idx 大小与 .ifo 的 idxfilesize 不一致"
        }
        SparseStarDictIndex.build(
            indexFile = idxFile,
            sparseFile = File(staging, SPARSE_FILE),
            offsetBits = info.indexOffsetBits,
            expectedCount = info.wordCount,
            dictionaryDataLength = dictFile.length()
        )

        val dictionary = OfflineDictionary(dictionaryId, info.bookName, info.wordCount)
        writeMetadata(staging, dictionary, info)
        val finalDirectory = File(rootDirectory, dictionaryId)
        require(!finalDirectory.exists()) { "同一内置词典已安装" }
        require(staging.renameTo(finalDirectory)) { "无法保存词典文件" }
        return DictionaryImportResult(dictionary)
    }

    private fun removeIncompleteImports() {
        rootDirectory.listFiles()?.forEach { file ->
            val valid = file.isDirectory && !file.name.startsWith(".") && File(file, METADATA_FILE).isFile
            if (!valid) file.deleteRecursively()
        }
    }

    private fun removeLegacySqliteIndex() {
        context.deleteDatabase("offline_dictionary_index.db")
    }

    private fun readRecord(file: File, offset: Long, size: Long): ByteArray {
        require(size <= Int.MAX_VALUE) { "单条释义过大" }
        return RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            ByteArray(size.toInt()).also(input::readFully)
        }
    }

    private fun copyDocument(source: DocumentFile, target: File, compressed: Boolean) {
        val raw = context.contentResolver.openInputStream(source.uri) ?: error("无法读取 ${source.name}")
        raw.use { input ->
            val decoded = if (compressed) GZIPInputStream(BufferedInputStream(input)) else BufferedInputStream(input)
            decoded.use { sourceStream ->
                BufferedOutputStream(FileOutputStream(target)).use { output -> sourceStream.copyTo(output) }
            }
        }
    }

    private fun findNamed(files: List<DocumentFile>, expected: String): DocumentFile? =
        files.firstOrNull { it.name.equals(expected, ignoreCase = true) }

    companion object {
        const val FORMAT_VERSION = 2
        const val LOOKUP_LIMIT = 40
        const val INFO_FILE = "dictionary.ifo"
        const val INDEX_FILE = "dictionary.idx"
        const val DICT_FILE = "dictionary.dict"
        const val SPARSE_FILE = "dictionary.sparse"
        const val METADATA_FILE = "metadata.properties"
        const val BUNDLED_ECDICT_ASSET = "ecdict-stardict-28.zip"
        const val BUNDLED_ECDICT_ID = "bundled-ecdict-28"
    }
}
