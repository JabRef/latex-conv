package org.jabref.latexconv.internal;

import org.jabref.latexconv.ConversionOptions;

import org.jspecify.annotations.Nullable;
import uk.ac.ed.ph.snuggletex.definitions.ComputedStyle;

/// Renders the token tree as a minimal HTML fragment: text styles become `<i>`/`<em>`/`<b>`/
/// `<code>`, scripts become `<sub>`/`<sup>`, text is `&<>`-escaped. Under
/// [ConversionOptions.MathMode#PASSTHROUGH] math spans stay verbatim and unescaped so a
/// downstream math renderer can pick them up.
public final class HtmlEmitter extends TokenWalker {

    public HtmlEmitter(ConversionOptions options) {
        super(options);
    }

    @Override
    protected void appendText(StringBuilder out, String text, @Nullable ComputedStyle style) {
        String tag = style == null ? null : switch (style.getFontFamily()) {
            case IT, SL -> "i";
            case EM -> "em";
            case BF -> "b";
            case TT -> "code";
            default -> null;
        };
        if (tag == null) {
            appendPlain(out, text);
        } else {
            out.append('<').append(tag).append('>');
            appendPlain(out, text);
            out.append("</").append(tag).append('>');
        }
    }

    @Override
    protected void appendPlain(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
    }

    @Override
    protected void appendScript(StringBuilder out, String renderedContent, boolean superscript) {
        String tag = superscript ? "sup" : "sub";
        out.append('<').append(tag).append('>').append(renderedContent).append("</").append(tag).append('>');
    }

    @Override
    protected void appendQuoted(StringBuilder out, String renderedContent) {
        out.append('“').append(renderedContent).append('”');
    }

    @Override
    protected void appendEmphasized(StringBuilder out, String renderedContent) {
        out.append("<em>").append(renderedContent).append("</em>");
    }

    @Override
    protected void appendMathPassthrough(StringBuilder out, String source) {
        out.append(source);
    }

    @Override
    protected boolean scriptsAsSource() {
        // Scripts always render as <sub>/<sup> tags: the HTML fragment is for display, not for
        // round-tripping back to LaTeX
        return false;
    }
}
