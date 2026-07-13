package org.jabref.latexconv;

/// Tuning knobs for LaTeX conversions. Obtain via [#defaults()] and derive variants with the
/// `with*` methods.
///
/// @param convertScripts whether `\textsuperscript{..}`, `\textsubscript{..}`, and math `^`/`_`
///        are converted to Unicode super-/subscript characters. Disable to keep them verbatim so
///        that a later Unicode→LaTeX conversion can round-trip
///        (see [JabRef issue 3644](https://github.com/JabRef/jabref/issues/3644))
/// @param mathMode how `$...$` / `\[...\]` spans are handled
/// @param unknownCommandPolicy how commands absent from the conversion table are handled
public record ConversionOptions(
        boolean convertScripts,
        MathMode mathMode,
        UnknownCommandPolicy unknownCommandPolicy) {

    /// Handling of math spans.
    public enum MathMode {
        /// Best-effort conversion of math content to Unicode (`$\pi$` → `π`)
        UNICODE,
        /// Math spans are emitted verbatim, delimiters included, for a downstream math renderer
        PASSTHROUGH
    }

    /// Handling of commands that neither the parser's vocabulary nor the conversion table covers.
    public enum UnknownCommandPolicy {
        /// Emit the command and its brace arguments exactly as written (`\aaaa{bbbb}`)
        KEEP_COMMAND,
        /// Drop the command but keep its argument content (`\aaaa{bbbb}` → `bbbb`)
        UNWRAP,
        /// Drop the command including its arguments
        DROP
    }

    /// `convertScripts = true`, [MathMode#UNICODE], [UnknownCommandPolicy#KEEP_COMMAND].
    public static ConversionOptions defaults() {
        return new ConversionOptions(true, MathMode.UNICODE, UnknownCommandPolicy.KEEP_COMMAND);
    }

    public ConversionOptions withConvertScripts(boolean convertScripts) {
        return new ConversionOptions(convertScripts, mathMode, unknownCommandPolicy);
    }

    public ConversionOptions withMathMode(MathMode mathMode) {
        return new ConversionOptions(convertScripts, mathMode, unknownCommandPolicy);
    }

    public ConversionOptions withUnknownCommandPolicy(UnknownCommandPolicy unknownCommandPolicy) {
        return new ConversionOptions(convertScripts, mathMode, unknownCommandPolicy);
    }
}
