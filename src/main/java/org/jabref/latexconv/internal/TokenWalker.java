package org.jabref.latexconv.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jabref.latexconv.ConversionOptions;

import org.jspecify.annotations.Nullable;
import uk.ac.ed.ph.snuggletex.InputError;
import uk.ac.ed.ph.snuggletex.SnuggleSession;
import uk.ac.ed.ph.snuggletex.definitions.ComputedStyle;
import uk.ac.ed.ph.snuggletex.definitions.CoreErrorCode;
import uk.ac.ed.ph.snuggletex.internal.FrozenSlice;
import uk.ac.ed.ph.snuggletex.internal.WorkingDocument;
import uk.ac.ed.ph.snuggletex.tokens.BraceContainerToken;
import uk.ac.ed.ph.snuggletex.tokens.CommandToken;
import uk.ac.ed.ph.snuggletex.tokens.EnvironmentToken;
import uk.ac.ed.ph.snuggletex.tokens.ErrorToken;
import uk.ac.ed.ph.snuggletex.tokens.FlowToken;
import uk.ac.ed.ph.snuggletex.tokens.MathCharacterToken;

/// Walks a parsed SnuggleTeX token tree and renders it through the output hooks implemented by
/// [UnicodeEmitter] and [HtmlEmitter], driven by [ConversionTable].
///
/// Recoverable parse errors are handled inline: unknown commands (TTEC00) are reproduced from the
/// source per [ConversionOptions.UnknownCommandPolicy], and stray `_`/`^` in text mode (TTEM03)
/// render as `_(...)`/`^(...)`. Any other parse error aborts via [UnsupportedLatexException],
/// which the public API translates into returning the normalized input.
public abstract class TokenWalker {

    /// Math-mode accent commands expressed as the table's short accent spellings.
    private static final Map<String, String> MATH_ACCENT_ALIASES = Map.of(
            "acute", "\\'",
            "grave", "\\`",
            "hat", "\\^",
            "tilde", "\\~",
            "bar", "\\=",
            "dot", "\\.",
            "ddot", "\\\"",
            "check", "\\v",
            "breve", "\\u");

    private static final Map<String, Map<Integer, Integer>> STYLE_MAP_CACHE = new ConcurrentHashMap<>();

    protected final ConversionOptions options;

    protected TokenWalker(ConversionOptions options) {
        this.options = options;
    }

    // Output hooks. "Plain" text must be shown literally (HTML escapes it); rendered content has
    // already been produced by a nested walk and passes through untouched.

    /// A text run from the source, with the style SnuggleTeX computed for it (`\textit{..}` and
    /// friends survive only here). `text` already has `~` resolved to no-break space.
    protected abstract void appendText(StringBuilder out, String text, @Nullable ComputedStyle style);

    /// Literal output text (converted characters, preserved commands, math characters).
    protected abstract void appendPlain(StringBuilder out, String text);

    /// A super-/subscript whose content was already rendered by a nested walk.
    protected abstract void appendScript(StringBuilder out, String renderedContent, boolean superscript);

    /// `\enquote{..}`/`\mkbibquote{..}` content, already rendered.
    protected abstract void appendQuoted(StringBuilder out, String renderedContent);

    /// `\mkbibemph{..}` content, already rendered.
    protected abstract void appendEmphasized(StringBuilder out, String renderedContent);

    /// A math span emitted verbatim under [ConversionOptions.MathMode#PASSTHROUGH], delimiters
    /// included.
    protected abstract void appendMathPassthrough(StringBuilder out, String source);

    /// Whether scripts should be emitted as their verbatim source instead of converted
    /// (the `convertScripts=false` round-trip mode; meaningless for HTML output).
    protected abstract boolean scriptsAsSource();

    public String emit(SnuggleSession session) {
        for (InputError error : session.getErrors()) {
            if (error.getErrorCode() != CoreErrorCode.TTEC00 && error.getErrorCode() != CoreErrorCode.TTEM03) {
                throw new UnsupportedLatexException("Unrecoverable parse error " + error.getErrorCode());
            }
        }
        return walk(session.getParsedTokens());
    }

    protected String walk(List<FlowToken> tokens) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < tokens.size()) {
            i = emitToken(tokens, i, out);
        }
        return out.toString();
    }

    /// Emits the token at `index` (possibly consuming look-ahead siblings, e.g. an accent's
    /// operand) and returns the index of the next unconsumed token.
    private int emitToken(List<FlowToken> tokens, int index, StringBuilder out) {
        FlowToken token = tokens.get(index);
        return switch (token.getType()) {
            case ERROR -> emitError(tokens, index, out);
            case COMMAND -> emitCommand(tokens, index, out);
            case ENVIRONMENT -> {
                emitEnvironment((EnvironmentToken) token, out);
                yield index + 1;
            }
            case BRACE_CONTAINER -> {
                out.append(walk(((BraceContainerToken) token).getContents()));
                yield index + 1;
            }
            case TEXT_MODE_TEXT, VERBATIM_MODE_TEXT -> {
                // Plain ~ is LaTeX's non-breaking space; the tokenizer leaves it inside text runs
                String text = token.getSlice().extract().toString().replace('~', '\u00A0');
                appendText(out, text, token.getComputedStyle());
                yield index + 1;
            }
            case MATH_CHARACTER -> {
                appendPlain(out, ((MathCharacterToken) token).getMathCharacter().getChars());
                yield index + 1;
            }
            default -> {
                appendPlain(out, token.getSlice().extract().toString());
                yield index + 1;
            }
        };
    }

    private int emitCommand(List<FlowToken> tokens, int index, StringBuilder out) {
        CommandToken command = (CommandToken) tokens.get(index);
        String name = command.getCommand().getTeXName();
        switch (name) {
            case "<paragraph>" -> out.append(walkArgument(command, 0));
            case "<msupormover>", "<msubormunder>" -> {
                boolean superscript = "<msupormover>".equals(name);
                if (scriptsAsSource()) {
                    appendPlain(out, command.getSlice().extract().toString());
                    return index + 1;
                }
                out.append(walkArgument(command, 0));
                appendScript(out, walkArgument(command, 1), superscript);
            }
            case "textsuperscript", "textsubscript" -> {
                if (scriptsAsSource()) {
                    appendPlain(out, command.getSlice().extract().toString());
                    return index + 1;
                }
                appendScript(out, walkArgument(command, 0), "textsuperscript".equals(name));
            }
            case "enquote", "mkbibquote" -> appendQuoted(out, walkArgument(command, 0));
            case "mkbibemph" -> appendEmphasized(out, walkArgument(command, 0));
            default -> {
                return emitGenericCommand(tokens, index, command, name, out);
            }
        }
        return index + 1;
    }

    private int emitGenericCommand(List<FlowToken> tokens, int index, CommandToken command, String name, StringBuilder out) {
        Optional<String> combining = accentCombining(name);
        if (combining.isPresent()) {
            // Text-mode accents carry their operand as combiner target or argument; math-mode
            // accents (\acute) stand alone, their operand following as the next sibling
            String base;
            int next = index + 1;
            if (command.getCombinerTarget() != null) {
                base = walk(command.getCombinerTarget().getContents());
            } else if (hasArgument(command, 0)) {
                base = walkArgument(command, 0);
            } else if (next < tokens.size()) {
                StringBuilder baseBuilder = new StringBuilder();
                next = emitToken(tokens, next, baseBuilder);
                base = baseBuilder.toString();
            } else {
                base = "";
            }
            out.append(applyCombining(base, combining.get()));
            return next;
        }

        String slice = command.getSlice().extract().toString();
        Optional<String> bySlice = ConversionTable.commandToUnicode(slice);
        if (bySlice.isPresent()) {
            appendPlain(out, bySlice.get());
            return index + 1;
        }
        Optional<String> byName = ConversionTable.commandToUnicode("\\" + name);
        if (byName.isPresent()) {
            appendPlain(out, byName.get());
            return index + 1;
        }
        if (!styleMap(name).isEmpty()) {
            appendPlain(out, mapThroughStyle(walkArgument(command, 0), name));
            return index + 1;
        }
        if (name.length() == 1 && !Character.isLetter(name.charAt(0))) {
            // Escaped specials (\$, \%, \&, \#, \{, \}) denote the character itself
            appendPlain(out, name);
            return index + 1;
        }
        switch (options.unknownCommandPolicy()) {
            case KEEP_COMMAND -> appendPlain(out, slice);
            case UNWRAP -> out.append(walkArgument(command, 0));
            case DROP -> {
            }
        }
        return index + 1;
    }

    private void emitEnvironment(EnvironmentToken environment, StringBuilder out) {
        String name = environment.getEnvironment().getTeXName();
        if (("math".equals(name) || "displaymath".equals(name))
                && options.mathMode() == ConversionOptions.MathMode.PASSTHROUGH) {
            appendMathPassthrough(out, environment.getSlice().extract().toString());
            return;
        }
        out.append(walk(environment.getContent().getContents()));
    }

    private int emitError(List<FlowToken> tokens, int index, StringBuilder out) {
        ErrorToken error = (ErrorToken) tokens.get(index);
        FrozenSlice slice = error.getSlice();
        WorkingDocument document = slice.getDocument();

        if (error.getError().getErrorCode() == CoreErrorCode.TTEM03) {
            // Stray _ or ^ in text mode. `_{...}` renders as `_(...)`: the braces are absent from
            // the token stream, so the group's extent comes from the source document
            String character = slice.extract().toString();
            if (document.charAt(slice.getEndIndex()) == '{') {
                int close = findBalancedClose(document, slice.getEndIndex());
                if (close >= 0) {
                    int next = index + 1;
                    StringBuilder inner = new StringBuilder();
                    while (next < tokens.size() && tokens.get(next).getSlice().getEndIndex() <= close) {
                        next = emitToken(tokens, next, inner);
                    }
                    appendPlain(out, character + "(");
                    out.append(inner);
                    appendPlain(out, ")");
                    return next;
                }
            }
            appendPlain(out, character);
            return index + 1;
        }

        // Accent commands the tokenizer doesn't know (\= \. - single-char control sequences
        // cannot be registered by name). The operand's braces are stripped and its text may even
        // be merged with what follows, so emit the next sibling whole and attach the combining
        // mark to its first codepoint
        Optional<String> combining = ConversionTable.combiningForAccent(slice.extract().toString());
        if (combining.isPresent()) {
            int next = index + 1;
            StringBuilder base = new StringBuilder();
            if (next < tokens.size()) {
                next = emitToken(tokens, next, base);
            }
            out.append(applyCombining(base.toString(), combining.get()));
            return next;
        }

        // TTEC00, unknown command: its brace groups are detached in the token stream (content
        // tokens follow as siblings, braces gone), so reproduce the exact source span
        int end = slice.getEndIndex();
        while (end < document.length() && document.charAt(end) == '{') {
            int close = findBalancedClose(document, end);
            if (close < 0) {
                break;
            }
            end = close + 1;
        }
        switch (options.unknownCommandPolicy()) {
            case KEEP_COMMAND -> {
                appendPlain(out, document.extract(slice.getStartIndex(), end).toString());
                return skipTokensBefore(tokens, index + 1, end);
            }
            case UNWRAP -> {
                return index + 1;
            }
            case DROP -> {
                return skipTokensBefore(tokens, index + 1, end);
            }
        }
        return index + 1;
    }

    private static int skipTokensBefore(List<FlowToken> tokens, int index, int documentEnd) {
        while (index < tokens.size() && tokens.get(index).getSlice().getEndIndex() <= documentEnd) {
            index++;
        }
        return index;
    }

    private static int findBalancedClose(WorkingDocument document, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < document.length(); i++) {
            int c = document.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /// Inserts the combining mark after the base's first codepoint: the accent belongs to the
    /// first letter even when the tokenizer hands over a longer run (`\'nski` -> `ński`).
    private static String applyCombining(String base, String combining) {
        if (base.isEmpty()) {
            return combining;
        }
        int firstEnd = base.offsetByCodePoints(0, 1);
        return base.substring(0, firstEnd) + combining + base.substring(firstEnd);
    }

    protected static String mapThroughStyle(String text, String styleCommandName) {
        Map<Integer, Integer> map = styleMap(styleCommandName);
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> result.appendCodePoint(map.getOrDefault(cp, cp)));
        return result.toString();
    }

    protected static Map<Integer, Integer> styleMap(String styleCommandName) {
        return STYLE_MAP_CACHE.computeIfAbsent(styleCommandName, ConversionTable::styleMap);
    }

    private static Optional<String> accentCombining(String commandName) {
        Optional<String> direct = ConversionTable.combiningForAccent("\\" + commandName);
        if (direct.isPresent()) {
            return direct;
        }
        String alias = MATH_ACCENT_ALIASES.get(commandName);
        return alias == null ? Optional.empty() : ConversionTable.combiningForAccent(alias);
    }

    private String walkArgument(CommandToken command, int argumentIndex) {
        if (!hasArgument(command, argumentIndex)) {
            return "";
        }
        return walk(command.getArguments()[argumentIndex].getContents());
    }

    private static boolean hasArgument(CommandToken command, int argumentIndex) {
        return command.getArguments() != null && command.getArguments().length > argumentIndex;
    }
}
