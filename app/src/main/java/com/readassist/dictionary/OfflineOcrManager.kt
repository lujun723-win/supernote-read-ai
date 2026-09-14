package com.readassist.dictionary

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.readassist.model.OcrLanguage
import java.util.Locale

class OfflineOcrManager {
    fun recognize(
        bitmap: Bitmap,
        language: OcrLanguage,
        onSuccess: (String) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val recognizer = when (language) {
            OcrLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrLanguage.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                recognizer.close()
                val text = result.text
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .lowercase(Locale.ROOT)
                onSuccess(text)
            }
            .addOnFailureListener { error ->
                recognizer.close()
                onFailure(error)
            }
    }
}
