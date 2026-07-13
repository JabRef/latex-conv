package org.jabref.latexconv.internal;

import java.util.Map;
import java.util.OptionalInt;

import org.jabref.latexconv.ConversionOptions;

import org.jspecify.annotations.Nullable;
import uk.ac.ed.ph.snuggletex.definitions.ComputedStyle;

/// Renders the token tree as Unicode plain text: styles map through the math-alphabet tables,
/// scripts become Unicode super-/subscript characters (or `^(...)`/`_(...)` when a character has
/// no script form).
public final class UnicodeEmitter extends TokenWalker {

    /// SnuggleTeX resolves `\textit{..}`/`\emph{..}`/... into anonymous style environments; the
    /// style survives only on the content tokens' [ComputedStyle]. Slanted/emphasized collapse to
    /// italic, matching how the current JabRef conversion renders `\textit` as math italics.
    private static final Map<ComputedStyle.FontFamily, String> FONT_TO_STYLE_COMMAND = Map.of(
            ComputedStyle.FontFamily.IT, "mathit",
            ComputedStyle.FontFamily.EM, "mathit",
            ComputedStyle.FontFamily.SL, "mathit",
            ComputedStyle.FontFamily.BF, "mathbf",
            ComputedStyle.FontFamily.TT, "mathtt");

    public UnicodeEmitter(ConversionOptions options) {
        super(options);
    }

    @Override
    protected void appendText(StringBuilder out, String text, @Nullable ComputedStyle style) {
        String styleCommand = style == null ? null : FONT_TO_STYLE_COMMAND.get(style.getFontFamily());
        out.append(styleCommand == null ? text : mapThroughStyle(text, styleCommand));
    }

    @Override
    protected void appendPlain(StringBuilder out, String text) {
        out.append(text);
    }

    @Override
    protected void appendScript(StringBuilder out, String renderedContent, boolean superscript) {
        StringBuilder mapped = new StringBuilder();
        for (int cp : renderedContent.codePoints().toArray()) {
            OptionalInt styled = superscript ? ConversionTable.superscriptFor(cp) : ConversionTable.subscriptFor(cp);
            if (styled.isPresent()) {
                mapped.appendCodePoint(styled.getAsInt());
            } else if (ConversionTable.baseForSuperscript(cp).isPresent() || ConversionTable.baseForSubscript(cp).isPresent()) {
                // Already a script character from a nested conversion (x^{d_1} -> xᵈ₁)
                mapped.appendCodePoint(cp);
            } else {
                // No script form for some character: fall back to readable ^(...)/_(...);
                // a single character needs no grouping parentheses (x_ℚ, not x_(ℚ))
                out.append(superscript ? '^' : '_');
                if (renderedContent.codePointCount(0, renderedContent.length()) > 1) {
                    out.append('(').append(renderedContent).append(')');
                } else {
                    out.append(renderedContent);
                }
                return;
            }
        }
        out.append(mapped);
    }

    @Override
    protected void appendQuoted(StringBuilder out, String renderedContent) {
        out.append('“').append(renderedContent).append('”');
    }

    @Override
    protected void appendEmphasized(StringBuilder out, String renderedContent) {
        out.append(mapThroughStyle(renderedContent, "mathit"));
    }

    @Override
    protected void appendMathPassthrough(StringBuilder out, String source) {
        out.append(source);
    }

    @Override
    protected boolean scriptsAsSource() {
        return !options.convertScripts();
    }
}
