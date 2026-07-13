package org.jabref.latexconv;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Ported from JabRef's `UnicodeToLatexFormatterTest`, with documented divergences: unmappable
/// characters are kept instead of dropped, and command emissions carry a single brace group
/// (`{\aa}`, where the old formatter produced `{{\aa}}`).
class UnicodeToLatexTest {

    private static Stream<Arguments> testCases() {
        return Stream.of(
                Arguments.of("", ""),
                Arguments.of("abc", "abc"),
                Arguments.of("{\\aa}{\\\"{a}}{\\\"{o}}", "åäö"),
                // divergence: kept, not dropped
                Arguments.of("\u0081", "\u0081"),
                Arguments.of("M{\\\"{o}}nch", "Mönch"),
                Arguments.of("{\\i}", "ı"),
                Arguments.of("{\\i} {\\={\\i}}", "ı ī"),
                // decomposed input normalizes to the same encoding as precomposed ī
                Arguments.of("{\\={\\i}}{\\={\\i}}", "īī"),
                Arguments.of("Pu{\\d{n}}ya-pattana-vidy{\\={a}}-p{\\={\\i}}{\\d{t}}h{\\={a}}dhi-k{\\d{r}}tai{\\d{h}} pr{\\={a}}-ka{\\'{s}}ya{\\d{m}} n{\\={\\i}}ta{\\d{h}}",
                        "Puṇya-pattana-vidyā-pīṭhādhi-kṛtaiḥ prā-kaśyaṃ nītaḥ")
        );
    }

    @ParameterizedTest
    @MethodSource("testCases")
    void toLatex(String expected, String input) {
        assertEquals(expected, LatexConv.toLatex(input));
    }

    @Test
    void piEncodesAsMathPi() {
        // JabRef issue #7291: π used to encode as $\phi$
        assertEquals("$\\pi$", LatexConv.toLatex("π"));
    }

    @Test
    void superscriptRunMergesIntoOneCommand() {
        // JabRef issue #3644: the roundtrip fix
        assertEquals("BPEL\\textsuperscript{light}", LatexConv.toLatex("BPELˡⁱᵍʰᵗ"));
    }

    @Test
    void subscriptRunMergesIntoOneCommand() {
        assertEquals("x\\textsubscript{i}", LatexConv.toLatex("xᵢ"));
    }

    @Test
    void ordinalRoundtrip() {
        assertEquals("4\\textsuperscript{th}", LatexConv.toLatex(LatexConv.toUnicode("4\\textsuperscript{th}")));
    }
}
