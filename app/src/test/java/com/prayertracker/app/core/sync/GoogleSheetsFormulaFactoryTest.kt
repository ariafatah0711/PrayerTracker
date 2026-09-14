package com.prayertracker.app.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSheetsFormulaFactoryTest {

    @Test
    fun `weekly formula uses locale separator consistently and avoids array literals`() {
        val formula = GoogleSheetsFormulaFactory(";").weeklySummary()

        assertTrue(formula.startsWith("=IFERROR(LET("))
        assertTrue(formula.contains("MAKEARRAY(ROWS(weekStarts); 35; LAMBDA("))
        assertTrue(formula.contains("CHOOSE(MOD(columnIndex-1; 5)+1; \"Subuh\""))
        assertFalse(formula.contains("{\"Subuh\""))
        assertTrue(formula.contains("\u2713"))
        assertTrue(hasBalancedParentheses(formula))
    }

    @Test
    fun `weekly formula supports comma locale`() {
        val formula = GoogleSheetsFormulaFactory(",").weeklySummary()

        assertTrue(formula.contains("MAKEARRAY(ROWS(weekStarts), 35, LAMBDA("))
        assertTrue(formula.contains("CHOOSE(MOD(columnIndex-1, 5)+1, \"Subuh\""))
        assertFalse(formula.contains("; 35;"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `factory rejects unsupported separator`() {
        GoogleSheetsFormulaFactory("|")
    }

    private fun hasBalancedParentheses(formula: String): Boolean {
        var depth = 0
        var insideString = false
        formula.forEachIndexed { index, character ->
            if (character == '"' && formula.getOrNull(index - 1) != '\\') insideString = !insideString
            if (!insideString) {
                when (character) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth < 0) return false
            }
        }
        return depth == 0 && !insideString
    }
}
