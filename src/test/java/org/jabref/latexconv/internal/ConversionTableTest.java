package org.jabref.latexconv.internal;

import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionTableTest {

    @Test
    void tableLoadsAndHasSubstantialRowCount() {
        assertTrue(ConversionTable.size() > 800, "expected > 800 rows, got " + ConversionTable.size());
    }

    @Test
    void alphaDecodesToGreekLetter() {
        assertEquals(Optional.of("α"), ConversionTable.commandToUnicode("\\alpha"));
    }

    @Test
    void greekAlphaEncodesBackToAlphaCommand() {
        Optional<String> latex = ConversionTable.latexForCodePoint('α');
        assertTrue(latex.isPresent());
        assertTrue(latex.get().contains("alpha"), "expected an \\alpha spelling, got " + latex);
    }

    @Test
    void umlautADecodesViaDirectLetterRow() {
        // The generated table's `letters` category carries a direct Biber-derived spelling for
        // "a with diaeresis" (\"{a}) rather than only a bare combining-accent row, so decode goes
        // straight through commandToUnicode rather than composing combiningForAccent + base char.
        assertEquals(Optional.of("ä"), ConversionTable.commandToUnicode("\\\"{a}"));
    }

    @Test
    void combiningForAcuteAccentReturnsCombiningMark() {
        // U+0301 COMBINING ACUTE ACCENT
        assertEquals(Optional.of("́"), ConversionTable.combiningForAccent("\\'"));
    }

    @Test
    void superscriptTRoundTripsWithBaseT() {
        OptionalInt styled = ConversionTable.superscriptFor('t');
        assertTrue(styled.isPresent());
        assertEquals('ᵗ', styled.getAsInt());

        OptionalInt base = ConversionTable.baseForSuperscript('ᵗ');
        assertTrue(base.isPresent());
        assertEquals('t', base.getAsInt());
    }

    @Test
    void mathbbStyleMapsCapitalAToDoubleStruckA() {
        Integer doubleStruckA = ConversionTable.styleMap("mathbb").get((int) 'A');
        assertEquals(Integer.valueOf(0x1D538), doubleStruckA);
    }

    @Test
    void encodeOnlyRowIsNotDecodable() {
        // "--" is Biber's preferred encode spelling for U+2013 (en dash), but the *same*
        // spelling was also historically claimed by U+2012 (figure dash); the generator resolves
        // that ambiguity by keeping U+2013 as the decode winner and marking U+2012's copy of
        // "\textendash" encode-only. That row must never come back out of commandToUnicode.
        assertFalse(ConversionTable.commandToUnicode("\\textendash").map(s -> s.codePointAt(0) == 0x2012)
                .orElse(false));
    }

    @Test
    void altSpellingStillDecodesToSameCodepointAsCanonicalRow() {
        // The generator never emits a `decode-only` row in practice (ambiguity is resolved via
        // `encode-only` on the losing spelling instead), so the more meaningful invariant to
        // assert here is: an `alt` spelling for a codepoint still decodes to that same codepoint
        // as the canonical (non-alt) row does. "\ldots" is an alt spelling of "\dots" for U+2026.
        Optional<String> altDecoded = ConversionTable.commandToUnicode("\\ldots");
        Optional<String> canonicalEncoded = ConversionTable.latexForCodePoint(0x2026);
        assertTrue(altDecoded.isPresent());
        assertTrue(canonicalEncoded.isPresent());
        assertEquals(altDecoded.get().codePointAt(0), 0x2026);
        assertEquals(Optional.of("\\dots"), canonicalEncoded);
    }
}
