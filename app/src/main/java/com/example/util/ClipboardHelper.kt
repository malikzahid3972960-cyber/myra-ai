package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

object ClipboardHelper {

    fun readText(context: Context): String? {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
            if (!clipboard.hasPrimaryClip()) return null
            val clip = clipboard.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            val item = clip.getItemAt(0) ?: return null
            val text = item.coerceToText(context)?.toString()
            text?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun writeText(context: Context, label: String, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun insertAtSelection(current: TextFieldValue, insertedText: String): TextFieldValue {
        val start = current.selection.min.coerceIn(0, current.text.length)
        val end = current.selection.max.coerceIn(0, current.text.length)
        val updatedText = buildString(current.text.length + insertedText.length) {
            append(current.text, 0, start)
            append(insertedText)
            append(current.text, end, current.text.length)
        }
        val newCursor = (start + insertedText.length).coerceIn(0, updatedText.length)
        return TextFieldValue(
            text = updatedText,
            selection = TextRange(newCursor)
        )
    }

    fun selectAll(current: TextFieldValue): TextFieldValue {
        return current.copy(selection = TextRange(0, current.text.length))
    }

    fun selectedTextOrAll(current: TextFieldValue): String {
        return if (!current.selection.collapsed) {
            val start = current.selection.min.coerceIn(0, current.text.length)
            val end = current.selection.max.coerceIn(0, current.text.length)
            current.text.substring(start, end)
        } else {
            current.text
        }
    }

    fun cutSelectionOrAll(current: TextFieldValue): Pair<String, TextFieldValue> {
        return if (!current.selection.collapsed) {
            val start = current.selection.min.coerceIn(0, current.text.length)
            val end = current.selection.max.coerceIn(0, current.text.length)
            val cut = current.text.substring(start, end)
            val remaining = current.text.removeRange(start, end)
            cut to TextFieldValue(text = remaining, selection = TextRange(start))
        } else {
            current.text to TextFieldValue(text = "", selection = TextRange(0))
        }
    }
}
