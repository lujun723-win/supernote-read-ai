package com.readassist.dictionary

import android.content.Context
import com.readassist.model.VocabularyLevel
import java.util.Locale
import java.util.zip.GZIPInputStream

data class RecognizedWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class VocabularyRecord(
    val word: String,
    val level: VocabularyLevel,
    val frequencyRank: Int,
    val gloss: String
)

data class VocabularyHint(
    val word: String,
    val gloss: String,
    val level: VocabularyLevel,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

fun interface VocabularyLookup {
    fun find(word: String): VocabularyRecord?
}

class VocabularyHintIndex(private val context: Context) : VocabularyLookup {
    @Volatile
    private var records: Map<String, VocabularyRecord>? = null

    override fun find(word: String): VocabularyRecord? = loadedRecords()[word]

    private fun loadedRecords(): Map<String, VocabularyRecord> {
        records?.let { return it }
        return synchronized(this) {
            records ?: load().also { records = it }
        }
    }

    private fun load(): Map<String, VocabularyRecord> {
        val result = HashMap<String, VocabularyRecord>(65_536)
        GZIPInputStream(context.assets.open(ASSET_NAME)).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filterNot { it.isBlank() || it.startsWith('#') }.forEach { line ->
                val fields = line.split('\t', limit = 4)
                if (fields.size != 4) return@forEach
                val level = fields[1].toIntOrNull()?.let(VocabularyLevel::fromDifficulty)
                    ?: return@forEach
                val rank = fields[2].toIntOrNull() ?: return@forEach
                result[fields[0]] = VocabularyRecord(fields[0], level, rank, fields[3])
            }
        }
        return result
    }

    companion object {
        private const val ASSET_NAME = "vocabulary_hints.dat"
    }
}

class VocabularyHintSelector(
    private val lookup: VocabularyLookup,
    private val maxHints: Int = 20
) {
    fun select(words: List<RecognizedWord>, threshold: VocabularyLevel): List<VocabularyHint> {
        val firstOccurrences = LinkedHashMap<String, RecognizedWord>()
        words.forEach { occurrence ->
            normalize(occurrence.text)?.let { word -> firstOccurrences.putIfAbsent(word, occurrence) }
        }

        return firstOccurrences.mapNotNull { (word, occurrence) ->
            val record = lookup.find(word) ?: return@mapNotNull null
            if (record.level.difficulty < threshold.difficulty) return@mapNotNull null
            VocabularyHint(
                word = word,
                gloss = record.gloss,
                level = record.level,
                left = occurrence.left,
                top = occurrence.top,
                right = occurrence.right,
                bottom = occurrence.bottom
            ) to record.frequencyRank
        }
            .sortedWith(
                compareByDescending<Pair<VocabularyHint, Int>> { it.first.level.difficulty }
                    .thenByDescending { it.second }
            )
            .take(maxHints)
            .map { it.first }
            .sortedWith(compareBy<VocabularyHint> { it.top }.thenBy { it.left })
    }

    private fun normalize(raw: String): String? {
        val value = raw.trim()
            .trim('\'', '‘', '’', '-')
            .lowercase(Locale.ROOT)
            .replace('’', '\'')
        return value.takeIf { WORD_REGEX.matches(it) }
    }

    companion object {
        private val WORD_REGEX = Regex("[a-z][a-z'-]{1,29}")
    }
}
