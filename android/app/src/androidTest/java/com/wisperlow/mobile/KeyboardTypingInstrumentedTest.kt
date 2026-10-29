package com.wisperlow.mobile

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.view.ContextThemeWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wisperlow.mobile.keyboard.EditorField
import com.wisperlow.mobile.keyboard.Lexicon
import com.wisperlow.mobile.keyboard.TypingOptions
import com.wisperlow.mobile.keyboard.TypingSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises composing spans against Android's real editor, including correction rollback. */
@RunWith(AndroidJUnit4::class)
class KeyboardTypingInstrumentedTest {
    @Test
    fun correctionUndoAndPunctuationUseRealComposingSpans() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = EditText(ContextThemeWrapper(instrumentation.targetContext, android.R.style.Theme_Material_Light))
            editor.requestFocus()
            val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val field = object : EditorField {
                override fun before(count: Int) = connection.getTextBeforeCursor(count, 0)?.toString().orEmpty()
                override fun after(count: Int) = connection.getTextAfterCursor(count, 0)?.toString().orEmpty()
                override fun setComposing(text: String) { connection.setComposingText(text, 1) }
                override fun commit(text: String) { connection.commitText(text, 1) }
                override fun finishComposing() { connection.finishComposingText() }
                override fun deleteBefore(count: Int) { connection.deleteSurroundingText(count, 0) }
                override fun backspaceKey() { connection.deleteSurroundingTextInCodePoints(1, 0) }
                override fun batch(block: () -> Unit) {
                    connection.beginBatchEdit()
                    try { block() } finally { connection.endBatchEdit() }
                }
            }
            val lexicon = Lexicon(listOf("the" to 100_000L, "hello" to 30_000L))
            val typing = TypingSession(field, { lexicon }).apply { options = TypingOptions(learn = false) }
            "teh".forEach { typing.type(it.toString()) }
            assertEquals("teh", editor.text.toString())
            typing.space()
            assertEquals("the ", editor.text.toString())
            typing.type(",")
            assertEquals("the,", editor.text.toString())
            typing.backspace()
            assertEquals("teh", editor.text.toString())
            typing.space()
            assertEquals("teh ", editor.text.toString())
            typing.type("h")
            typing.pick("hello")
            assertEquals("teh hello ", editor.text.toString())
            typing.backspace()
            assertEquals("teh hello", editor.text.toString())
            assertEquals("hello", typing.composing)
            connection.closeConnection()
        }
    }
}
