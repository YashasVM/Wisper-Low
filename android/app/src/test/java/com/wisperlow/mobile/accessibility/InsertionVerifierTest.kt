package com.wisperlow.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertionVerifierTest {
    @Test
    fun exactResultIsApplied() {
        assertEquals(
            InsertionVerifier.Outcome.APPLIED,
            InsertionVerifier.check("ab", "aXb", "X"),
        )
    }

    @Test
    fun unchangedFieldIsNotApplied_evenIfTextAlreadyContainsInsertion() {
        assertEquals(
            InsertionVerifier.Outcome.NOT_APPLIED,
            InsertionVerifier.check("hello world", "hello world", "hello"),
        )
    }

    @Test
    fun appAlteredTextCountsAsAppliedSoPasteDoesNotDoubleInsert() {
        assertEquals(
            InsertionVerifier.Outcome.APPLIED,
            InsertionVerifier.check("a", "A  hello", " hello"),
        )
    }

    @Test
    fun anyChangeCountsAsApplied() {
        assertEquals(
            InsertionVerifier.Outcome.APPLIED,
            InsertionVerifier.check("a", "", "hello"),
        )
    }

    @Test
    fun terminalsAreRecognisedByPackageOrClass() {
        assertTrue(InsertionVerifier.isTerminal("com.termux", null))
        assertTrue(InsertionVerifier.isTerminal("x.y", "com.foo.TerminalView"))
        assertFalse(InsertionVerifier.isTerminal("com.android.chrome", "android.widget.EditText"))
    }
}
