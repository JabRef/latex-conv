package org.jabref.latexconv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Behavioral contract ported from JabRef's `LatexToUnicodeFormatterTest`, which this library is
/// meant to replace (see JabRef issues
/// [#5547](https://github.com/JabRef/jabref/issues/5547),
/// [#3644](https://github.com/JabRef/jabref/issues/3644),
/// [#7291](https://github.com/JabRef/jabref/issues/7291),
/// [#2500](https://github.com/JabRef/jabref/issues/2500),
/// [#2498](https://github.com/JabRef/jabref/issues/2498),
/// [#2458](https://github.com/JabRef/jabref/issues/2458)).
class LatexToUnicodeTest {

    @Test
    void plainFormat() {
        assertEquals("aaa", LatexConv.toUnicode("aaa"));
    }

    @Test
    void formatUmlaut() {
        assertEquals("ä", LatexConv.toUnicode("{\\\"{a}}"));
        assertEquals("Ä", LatexConv.toUnicode("{\\\"{A}}"));
    }

    @ParameterizedTest
    @CsvSource({
            "ı, \\i",
            "ı, {\\i}"
    })
    void smallIwithoutDot(String expected, String input) {
        assertEquals(expected, LatexConv.toUnicode(input));
    }

    @Test
    void preserveUnknownCommand() {
        assertEquals("\\mbox{-}", LatexConv.toUnicode("\\mbox{-}"));
    }

    @Test
    void formatTextit() {
        assertEquals("𝑡𝑒𝑥𝑡", LatexConv.toUnicode("\\textit{text}"));
    }

    @Test
    void escapedDollarSign() {
        assertEquals("$", LatexConv.toUnicode("\\$"));
    }

    @Test
    void equationsSingleSymbol() {
        assertEquals("σ", LatexConv.toUnicode("$\\sigma$"));
    }

    @Test
    void equationsMoreComplicatedFormatting() {
        assertEquals("A 32 mA ΣΔ-modulator", LatexConv.toUnicode("A 32~{mA} {$\\Sigma\\Delta$}-modulator"));
    }

    @Test
    void chi() {
        assertEquals("χ", LatexConv.toUnicode("$\\chi$"));
    }

    @Test
    void sWithCaron() {
        assertEquals("Š", LatexConv.toUnicode("{\\v{S}}"));
    }

    @Test
    void iWithDiaresis() {
        assertEquals("ï", LatexConv.toUnicode("\\\"{i}"));
    }

    @Test
    void iWithDiaresisAndEscapedI() {
        // Renders identically to ï: dotless i has no precomposed form with diaeresis
        assertEquals("ı̈", LatexConv.toUnicode("\\\"{\\i}"));
    }

    @Test
    void iWithDiaresisAndUnnecessaryBraces() {
        assertEquals("ï", LatexConv.toUnicode("{\\\"{i}}"));
    }

    @Test
    void upperCaseIWithDiaresis() {
        assertEquals("Ï", LatexConv.toUnicode("\\\"{I}"));
    }

    @Test
    void polishName() {
        assertEquals("Łęski", LatexConv.toUnicode("\\L\\k{e}ski"));
    }

    @Test
    void doubleCombiningAccents() {
        assertEquals("ώ", LatexConv.toUnicode("$\\acute{\\omega}$"));
    }

    @Test
    void combiningAccentsCase1() {
        assertEquals("ḩ", LatexConv.toUnicode("{\\c{h}}"));
    }

    @Test
    void keepUnknownCommandWithoutArgument() {
        assertEquals("\\aaaa", LatexConv.toUnicode("\\aaaa"));
    }

    @Test
    void keepUnknownCommandWithArgument() {
        assertEquals("\\aaaa{bbbb}", LatexConv.toUnicode("\\aaaa{bbbb}"));
    }

    @Test
    void keepUnknownCommandWithEmptyArgument() {
        assertEquals("\\aaaa{}", LatexConv.toUnicode("\\aaaa{}"));
    }

    @Test
    void tildeN() {
        assertEquals("Montaña", LatexConv.toUnicode("Monta\\~{n}a"));
    }

    @Test
    void acuteNLongVersion() {
        assertEquals("Maliński", LatexConv.toUnicode("Mali\\'{n}ski"));
        assertEquals("MaliŃski", LatexConv.toUnicode("Mali\\'{N}ski"));
    }

    @Test
    void acuteNShortVersion() {
        assertEquals("Maliński", LatexConv.toUnicode("Mali\\'nski"));
        assertEquals("MaliŃski", LatexConv.toUnicode("Mali\\'Nski"));
    }

    @Test
    void apostrophN() {
        assertEquals("Mali'nski", LatexConv.toUnicode("Mali'nski"));
        assertEquals("Mali'Nski", LatexConv.toUnicode("Mali'Nski"));
    }

    @Test
    void apostrophO() {
        assertEquals("L'oscillation", LatexConv.toUnicode("L'oscillation"));
    }

    @Test
    void apostrophC() {
        assertEquals("O'Connor", LatexConv.toUnicode("O'Connor"));
    }

    @Test
    void preservationOfSingleUnderscore() {
        assertEquals("Lorem ipsum_lorem ipsum", LatexConv.toUnicode("Lorem ipsum_lorem ipsum"));
    }

    @Test
    void conversionOfUnderscoreWithBraces() {
        assertEquals("Lorem ipsum_(lorem ipsum)", LatexConv.toUnicode("Lorem ipsum_{lorem ipsum}"));
    }

    /// [JabRef issue 5547](https://github.com/JabRef/jabref/issues/5547)
    @Test
    void twoDifferentMacrons() {
        assertEquals("Puṇya-pattana-vidyā-pı̄ṭhādhi-kṛtaiḥ prā-kaśyaṃ nı̄taḥ",
                LatexConv.toUnicode("Pu{\\d{n}}ya-pattana-vidy{\\={a}}-p{\\={\\i}}{\\d{t}}h{\\={a}}dhi-k{\\d{r}}tai{\\d{h}} pr{\\={a}}-ka{{\\'{s}}}ya{\\d{m}} n{\\={\\i}}ta{\\d{h}}"));
    }

    @ParameterizedTest
    @CsvSource({
            "1ˢᵗ, 1\\textsuperscript{st}",
            "2ⁿᵈ, 2\\textsuperscript{nd}",
            "3ʳᵈ, 3\\textsuperscript{rd}",
            "4ᵗʰ, 4\\textsuperscript{th}",
            "9ᵗʰ, 9\\textsuperscript{th}"
    })
    void conversionOfOrdinals(String expected, String input) {
        assertEquals(expected, LatexConv.toUnicode(input));
    }

    @Test
    void formatPreservesNoBreakSpaces() {
        assertEquals("Y. Matsumoto", LatexConv.toUnicode("Y.~Matsumoto"));
    }

    @Test
    void enquoteRendersTypographicQuotes() {
        assertEquals("“quoted”", LatexConv.toUnicode("\\enquote{quoted}"));
    }

    @Test
    void backslashInMath() {
        assertEquals("\\", LatexConv.toUnicode("$\\backslash$"));
    }

    /// Math-mode readability, matching JabRef PR
    /// [#16203](https://github.com/JabRef/jabref/pull/16203): brackets survive, `\text`/
    /// `\operatorname` unwrap, combined `_{}^{}` scripts convert (single characters without a
    /// script form fall back without grouping parentheses), and source spacing survives.
    @ParameterizedTest
    @CsvSource({
            "'A=𝔽[x,y,z]/I', '$A=\\mathbb{F}[x,y,z]/I$'",
            "'(R)', '$(R)$'",
            "'char(𝔽)=0', '$\\text{char}(\\mathbb{F})=0$'",
            "'two words', '$\\text{two words}$'",
            "'edim(R)≥ 2', '$\\operatorname{edim}(R)\\ge 2$'",
            "'xₐᵇ', '$x_{a}^{b}$'",
            "'𝔹_ℚᵖᵘʳᵉ(R)', '$\\mathbb{B}_{\\mathbb{Q}}^{\\mathrm{pure}}(R)$'",
            "'𝔹_ℚ(R) = 𝔹(R)', '$\\mathbb{B}_{\\mathbb{Q}}(R) = \\mathbb{B}(R)$'"
    })
    void mathRemainsReadable(String expected, String input) {
        assertEquals(expected, LatexConv.toUnicode(input));
    }

    @ParameterizedTest
    @CsvSource({
            "'Cohen–Macaulay', 'Cohen--Macaulay'",
            "'pp. 1—2', 'pp. 1---2'"
    })
    void dashLigatures(String expected, String input) {
        assertEquals(expected, LatexConv.toUnicode(input));
    }

    /// Bibliography fields carry bare `%` as a literal character, never as a comment starter.
    @ParameterizedTest
    @CsvSource({
            "'History%20Textbook', 'History%20Textbook'",
            "'50% off', '50\\% off'"
    })
    void percentIsLiteral(String expected, String input) {
        assertEquals(expected, LatexConv.toUnicode(input));
    }
}
