package org.jabref.latexconv;

import java.text.Normalizer;

/// Converts LaTeX markup to Unicode plain text or minimal HTML, and Unicode text back to LaTeX.
///
/// All conversions are total: they never throw on malformed input and never return `null`.
/// When input cannot be converted, the NFC-normalized input is returned unchanged.
public final class LatexConv {

    private LatexConv() {
    }

    /// Converts LaTeX markup to Unicode plain text.
    ///
    /// Skeleton implementation: NFC-normalizes only.
    public static String toUnicode(String latex) {
        return Normalizer.normalize(latex, Normalizer.Form.NFC);
    }
}
