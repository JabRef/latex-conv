package org.jabref.latexconv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatexConvTest {

    @Test
    void toUnicodeNormalizesToNfc() {
        // "a" + combining diaeresis (U+0308) composes to the precomposed "ä"
        assertEquals("ä", LatexConv.toUnicode("ä"));
    }
}
