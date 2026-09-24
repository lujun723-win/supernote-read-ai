package com.readassist.service.managers

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.readassist.R
import com.readassist.dictionary.DictionaryDefinition
import com.readassist.model.DictionaryCaptureMode
import com.readassist.utils.PreferenceManager

class DictionaryWindowManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val preferenceManager: PreferenceManager,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onDictionaryLookupRequested(query: String)
        fun onDictionaryTextSelectionRequested()
        fun onDictionaryRegionOcrRequested()
        fun onVocabularyHintsRequested()
        fun onVocabularyHintsClearRequested()
    }

    private var window: View? = null
    private var queryInput: EditText? = null
    private var statusText: TextView? = null
    private var resultText: TextView? = null

    fun show(query: String = "", requestLookup: Boolean = false, showKeyboard: Boolean = true) {
        hide()
        val container = FrameLayout(context)
        val shade = View(context).apply {
            setBackgroundColor(0x80000000.toInt())
            setOnClickListener { hide() }
        }
        container.addView(shade, FrameLayout.LayoutParams(-1, -1))

        val content = LayoutInflater.from(context).inflate(R.layout.dictionary_window, container, false)
        val metrics = context.resources.displayMetrics
        container.addView(content, FrameLayout.LayoutParams(
            (metrics.widthPixels * 0.9f).toInt(),
            (metrics.heightPixels * 0.7f).toInt()
        ).apply { gravity = Gravity.CENTER })

        queryInput = content.findViewById(R.id.dictionaryQueryInput)
        statusText = content.findViewById(R.id.dictionaryStatusText)
        resultText = content.findViewById(R.id.dictionaryResultText)
        val searchButton: Button = content.findViewById(R.id.dictionarySearchButton)
        val closeButton: Button = content.findViewById(R.id.dictionaryCloseButton)
        val textSelectionButton: Button = content.findViewById(R.id.dictionaryTextSelectionButton)
        val regionOcrButton: Button = content.findViewById(R.id.dictionaryRegionOcrButton)
        val vocabularyButton: Button = content.findViewById(R.id.dictionaryVocabularyButton)
        val clearHintsButton: Button = content.findViewById(R.id.dictionaryClearHintsButton)
        val currentMode = preferenceManager.getDictionaryCaptureMode()
        textSelectionButton.setBackgroundColor(modeColor(currentMode == DictionaryCaptureMode.TEXT_SELECTION))
        regionOcrButton.setBackgroundColor(modeColor(currentMode == DictionaryCaptureMode.REGION_OCR))
        vocabularyButton.setBackgroundColor(modeColor(currentMode == DictionaryCaptureMode.VOCABULARY_HINTS))
        val submit = {
            val value = queryInput?.text?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) callbacks.onDictionaryLookupRequested(value)
        }
        searchButton.setOnClickListener { submit() }
        textSelectionButton.setOnClickListener { callbacks.onDictionaryTextSelectionRequested() }
        regionOcrButton.setOnClickListener { callbacks.onDictionaryRegionOcrRequested() }
        vocabularyButton.setOnClickListener { callbacks.onVocabularyHintsRequested() }
        clearHintsButton.setOnClickListener { callbacks.onVocabularyHintsClearRequested() }
        queryInput?.setOnEditorActionListener { _, _, _ -> submit(); true }
        closeButton.setOnClickListener { hide() }
        queryInput?.setText(query)
        queryInput?.setSelection(query.length)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowManager.addView(container, params)
        window = container

        if (requestLookup && query.isNotBlank()) {
            submit()
        } else {
            queryInput?.requestFocus()
            if (showKeyboard) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(queryInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun isShowing(): Boolean = window != null

    fun setQueryIfEmpty(query: String): Boolean {
        val input = queryInput ?: return false
        if (input.text?.isNotBlank() == true) return false
        val value = query.trim()
        if (value.isEmpty()) return false
        input.setText(value)
        input.setSelection(value.length)
        return true
    }

    fun showLoading(query: String) {
        statusText?.text = context.getString(R.string.dictionary_searching, query)
        resultText?.text = ""
    }

    fun showResults(query: String, results: List<DictionaryDefinition>) {
        statusText?.text = if (results.isEmpty()) {
            context.getString(R.string.dictionary_not_found, query)
        } else {
            context.resources.getQuantityString(R.plurals.dictionary_result_count, results.size, results.size)
        }
        resultText?.text = results.joinToString("\n\n————————\n\n") {
            "${it.headword}  ·  ${it.dictionaryName}\n\n${it.definition}"
        }
    }

    fun showError(message: String) {
        statusText?.text = message
        resultText?.text = ""
    }

    fun hide() {
        window?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // Window may already be detached by the system.
            }
        }
        window = null
        queryInput = null
        statusText = null
        resultText = null
    }

    private fun modeColor(selected: Boolean): Int =
        if (selected) 0xFFD6E9F8.toInt() else 0xFFF0F0F0.toInt()
}
