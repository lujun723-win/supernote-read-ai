package com.readassist.dictionary

data class StarDictInfo(
    val bookName: String,
    val wordCount: Long,
    val indexFileSize: Long,
    val indexOffsetBits: Int,
    val sameTypeSequence: String,
    val synonymCount: Long
)

data class StarDictIndexEntry(
    val headword: String,
    val offset: Long,
    val size: Long
)

data class OfflineDictionary(
    val id: String,
    val name: String,
    val wordCount: Long
)

data class DictionaryDefinition(
    val dictionaryName: String,
    val headword: String,
    val definition: String
)

data class DictionaryImportResult(
    val dictionary: OfflineDictionary
)
