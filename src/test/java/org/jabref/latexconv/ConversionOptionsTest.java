package org.jabref.latexconv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversionOptionsTest {

    @Test
    void superscriptStaysVerbatimWithScriptsDisabled() {
        // JabRef issue #3644: Unicode superscripts cannot round-trip back to LaTeX
        ConversionOptions options = ConversionOptions.defaults().withConvertScripts(false);
        assertEquals("BPEL\\textsuperscript{light}", LatexConv.toUnicode("BPEL\\textsuperscript{light}", options));
    }

    @Test
    void superscriptConvertsWithScriptsEnabled() {
        assertEquals("BPELˡⁱᵍʰᵗ", LatexConv.toUnicode("BPEL\\textsuperscript{light}"));
    }

    @Test
    void mathPassthroughKeepsSourceVerbatim() {
        ConversionOptions options = ConversionOptions.defaults().withMathMode(ConversionOptions.MathMode.PASSTHROUGH);
        assertEquals("Formula $E=mc^2$ here", LatexConv.toUnicode("Formula $E=mc^2$ here", options));
    }

    @Test
    void mathUnicodeConvertsScripts() {
        assertEquals("E=mc²", LatexConv.toUnicode("$E=mc^2$"));
    }

    @Test
    void nestedScriptsUseAvailableScriptCharacters() {
        // JabRef PR #16203 corpus: inner subscript survives inside the outer superscript
        assertEquals("xᵈ₁", LatexConv.toUnicode("$x^{d_1}$"));
    }

    @Test
    void unknownCommandUnwrapKeepsContent() {
        ConversionOptions options = ConversionOptions.defaults()
                .withUnknownCommandPolicy(ConversionOptions.UnknownCommandPolicy.UNWRAP);
        assertEquals("bbbb", LatexConv.toUnicode("\\aaaa{bbbb}", options));
    }

    @Test
    void unknownCommandDropRemovesEverything() {
        ConversionOptions options = ConversionOptions.defaults()
                .withUnknownCommandPolicy(ConversionOptions.UnknownCommandPolicy.DROP);
        assertEquals("", LatexConv.toUnicode("\\aaaa{bbbb}", options));
    }

    @Test
    void pi() {
        // JabRef issue #7291: $\pi$ used to render as literal "pi"
        assertEquals("π", LatexConv.toUnicode("$\\pi$"));
    }
}
