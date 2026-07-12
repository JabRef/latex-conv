package org.jabref.latexconv;

import java.io.IOException;
import java.text.Normalizer;

import org.jabref.latexconv.internal.SnuggleSupport;
import org.jabref.latexconv.internal.UnicodeEmitter;

import uk.ac.ed.ph.snuggletex.SnuggleInput;
import uk.ac.ed.ph.snuggletex.SnuggleSession;

/// Converts LaTeX markup to Unicode plain text or minimal HTML, and Unicode text back to LaTeX.
///
/// All conversions are total: they never throw on malformed input and never return `null`.
/// When input cannot be converted, the NFC-normalized input is returned unchanged.
public final class LatexConv {

    private LatexConv() {
    }

    /// Converts LaTeX markup to Unicode plain text using [ConversionOptions#defaults()].
    public static String toUnicode(String latex) {
        return toUnicode(latex, ConversionOptions.defaults());
    }

    /// Converts LaTeX markup to Unicode plain text: `Monta\~{n}a` → `Montaña`,
    /// `$\pi$` → `π`, `1\textsuperscript{st}` → `1ˢᵗ`.
    public static String toUnicode(String latex, ConversionOptions options) {
        try {
            SnuggleSession session = SnuggleSupport.createSession();
            session.parseInput(new SnuggleInput(latex));
            return Normalizer.normalize(UnicodeEmitter.emit(session, options), Normalizer.Form.NFC);
        } catch (IOException | RuntimeException e) {
            // Total-function contract: inputs we cannot convert come back normalized, not as an
            // exception (parse failures surface as UnsupportedLatexException, a RuntimeException)
            return Normalizer.normalize(latex, Normalizer.Form.NFC);
        }
    }
}
