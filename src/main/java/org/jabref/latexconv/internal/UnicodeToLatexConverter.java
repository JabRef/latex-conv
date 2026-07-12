package org.jabref.latexconv.internal;

import java.text.Normalizer;
import java.util.Optional;

/// Encodes Unicode plain text as LaTeX, the reverse of [UnicodeEmitter], from the same
/// conversion table.
///
/// Differences from JabRef's historical `UnicodeToLatexFormatter`: characters without a mapping
/// are kept as-is instead of being dropped, command emissions are wrapped in a single brace
/// group (`{\aa}`, not `{{\aa}}`), and runs of Unicode super-/subscript characters merge into
/// one `\textsuperscript{...}`/`\textsubscript{...}` so the conversion round-trips
/// (JabRef issue #3644).
public final class UnicodeToLatexConverter {

    private UnicodeToLatexConverter() {
    }

    public static String convert(String text) {
        int[] codePoints = Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().toArray();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < codePoints.length) {
            int cp = codePoints[i];

            if (ConversionTable.baseForSuperscript(cp).isPresent()) {
                i = emitScriptRun(codePoints, i, out, true);
                continue;
            }
            if (ConversionTable.baseForSubscript(cp).isPresent()) {
                i = emitScriptRun(codePoints, i, out, false);
                continue;
            }

            // NFC leftovers: a base followed by combining marks that have no precomposed form
            // (ı̄, ḫ-style sequences) encode as nested accent commands
            int marks = countMappedMarks(codePoints, i + 1);
            if (marks > 0) {
                out.append(accentForm(codePoints, i, marks));
                i += 1 + marks;
                continue;
            }

            if (cp < 128) {
                out.appendCodePoint(cp);
                i++;
                continue;
            }

            Optional<String> latex = ConversionTable.latexForCodePoint(cp);
            if (latex.isPresent()) {
                out.append(wrap(latex.get(), cp));
            } else {
                // No mapping: keep the character (bib files are UTF-8-capable; dropping loses data)
                out.appendCodePoint(cp);
            }
            i++;
        }
        return out.toString();
    }

    private static int emitScriptRun(int[] codePoints, int start, StringBuilder out, boolean superscript) {
        StringBuilder bases = new StringBuilder();
        int i = start;
        while (i < codePoints.length) {
            var base = superscript
                    ? ConversionTable.baseForSuperscript(codePoints[i])
                    : ConversionTable.baseForSubscript(codePoints[i]);
            if (base.isEmpty()) {
                break;
            }
            bases.appendCodePoint(base.getAsInt());
            i++;
        }
        out.append(superscript ? "\\textsuperscript{" : "\\textsubscript{").append(bases).append('}');
        return i;
    }

    /// Number of consecutive codepoints from `start` that are combining marks with a known
    /// accent command. Zero when the very first is unknown — the caller then falls back to
    /// emitting characters individually.
    private static int countMappedMarks(int[] codePoints, int start) {
        int count = 0;
        while (start + count < codePoints.length
                && Character.getType(codePoints[start + count]) == Character.NON_SPACING_MARK
                && ConversionTable.accentForCombining(codePoints[start + count]).isPresent()) {
            count++;
        }
        return count;
    }

    private static String accentForm(int[] codePoints, int baseIndex, int marks) {
        int base = codePoints[baseIndex];
        // Accents over i/j use the dotless variants in LaTeX
        String form = switch (base) {
            case 'i' -> "\\i";
            case 'j' -> "\\j";
            default -> {
                if (base < 128) {
                    yield Character.toString(base);
                }
                yield ConversionTable.latexForCodePoint(base).orElse(Character.toString(base));
            }
        };
        for (int m = 0; m < marks; m++) {
            String accent = ConversionTable.accentForCombining(codePoints[baseIndex + 1 + m]).orElseThrow();
            form = accent + "{" + form + "}";
        }
        return "{" + form + "}";
    }

    private static String wrap(String latex, int codePoint) {
        if (!latex.startsWith("\\")) {
            return latex;
        }
        if (ConversionTable.encodeNeedsMathMode(codePoint)) {
            return "$" + latex + "$";
        }
        return "{" + latex + "}";
    }
}
