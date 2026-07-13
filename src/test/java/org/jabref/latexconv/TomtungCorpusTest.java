package org.jabref.latexconv;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Selected cases from the test suite of
/// [tomtung/latex2unicode](https://github.com/tomtung/latex2unicode) (Apache-2.0, see NOTICE),
/// the library this one replaces in JabRef.
class TomtungCorpusTest {

    @ParameterizedTest
    @CsvSource(textBlock = """
            é           | \\'{e}
            ø           | \\o
            ß           | \\ss
            Ð           | \\DH
            þ           | \\th
            « guillemets » | \\guillemotleft{} guillemets \\guillemotright
            ¡           | \\textexclamdown
            ¿           | \\textquestiondown
            £           | \\pounds
            §           | \\S
            ©           | \\copyright
            †           | \\dag
            ‡           | \\ddag
            ∞           | $\\infty$
            ≤           | $\\leq$
            ≥           | $\\geq$
            ≠           | $\\neq$
            ±           | $\\pm$
            ∈           | $\\in$
            ℏ           | $\\hbar$
            ℵ           | $\\aleph$
            """, delimiter = '|')
    void decodesLikeTomtung(String expected, String input) {
        assertEquals(expected.trim(), LatexConv.toUnicode(input.trim()));
    }
}
