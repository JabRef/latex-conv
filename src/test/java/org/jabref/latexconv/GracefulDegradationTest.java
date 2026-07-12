package org.jabref.latexconv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The public API must never throw: inputs that cannot be parsed come back NFC-normalized.
class GracefulDegradationTest {

    @Test
    void emptyString() {
        assertEquals("", LatexConv.toUnicode(""));
    }

    @Test
    void unbalancedOpeningBrace() {
        assertEquals("{unclosed", LatexConv.toUnicode("{unclosed"));
    }

    @Test
    void unbalancedClosingBrace() {
        assertEquals("closed}", LatexConv.toUnicode("closed}"));
    }

    @Test
    void unclosedEnvironment() {
        assertEquals("\\begin{foo} bar", LatexConv.toUnicode("\\begin{foo} bar"));
    }

    @Test
    void loneBackslashAtEnd() {
        assertEquals("trailing\\", LatexConv.toUnicode("trailing\\"));
    }

    @Test
    void unclosedMath() {
        assertEquals("$\\alpha", LatexConv.toUnicode("$\\alpha"));
    }
}
