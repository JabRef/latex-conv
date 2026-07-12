package org.jabref.latexconv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatexToHtmlTest {

    @Test
    void plainTextStaysPlain() {
        assertEquals("aaa", LatexConv.toHtml("aaa"));
    }

    @Test
    void emphBecomesEmTag() {
        assertEquals("<em>a</em>", LatexConv.toHtml("\\emph{a}"));
    }

    @Test
    void textitBecomesItalicTag() {
        assertEquals("<i>text</i>", LatexConv.toHtml("\\textit{text}"));
    }

    @Test
    void textbfBecomesBoldTag() {
        assertEquals("<b>bold</b>", LatexConv.toHtml("\\textbf{bold}"));
    }

    @Test
    void textttBecomesCodeTag() {
        assertEquals("<code>mono</code>", LatexConv.toHtml("\\texttt{mono}"));
    }

    @Test
    void superscriptBecomesSupTag() {
        assertEquals("4<sup>th</sup>", LatexConv.toHtml("4\\textsuperscript{th}"));
    }

    @Test
    void subscriptBecomesSubTag() {
        assertEquals("x<sub>i</sub>", LatexConv.toHtml("x\\textsubscript{i}"));
    }

    @Test
    void accentsComposeToUnicode() {
        assertEquals("Montaña", LatexConv.toHtml("Monta\\~{n}a"));
    }

    @Test
    void markupCharactersAreEscaped() {
        assertEquals("a &amp; b &lt;c&gt;", LatexConv.toHtml("a \\& b <c>"));
    }

    @Test
    void mathScriptsBecomeTags() {
        assertEquals("E=mc<sup>2</sup>", LatexConv.toHtml("$E=mc^2$"));
    }

    @Test
    void mathConvertsToUnicodeByDefault() {
        assertEquals("π", LatexConv.toHtml("$\\pi$"));
    }

    @Test
    void mathPassthroughStaysUnescaped() {
        ConversionOptions options = ConversionOptions.defaults().withMathMode(ConversionOptions.MathMode.PASSTHROUGH);
        assertEquals("$E=mc^2 < 3$", LatexConv.toHtml("$E=mc^2 < 3$", options));
    }

    @Test
    void unknownCommandIsPreservedEscaped() {
        // KEEP_COMMAND reproduces the raw source (\& stays uninterpreted), HTML-escaped
        assertEquals("\\aaaa{bb\\&amp;bb}", LatexConv.toHtml("\\aaaa{bb\\&bb}"));
    }

    @Test
    void enquoteRendersTypographicQuotes() {
        assertEquals("“quoted”", LatexConv.toHtml("\\enquote{quoted}"));
    }

    @Test
    void unparseableInputDegradesToNormalizedInput() {
        assertEquals("{unclosed", LatexConv.toHtml("{unclosed"));
    }
}
