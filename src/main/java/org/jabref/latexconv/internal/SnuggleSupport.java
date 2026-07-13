package org.jabref.latexconv.internal;

import java.util.Map;
import java.util.TreeMap;

import uk.ac.ed.ph.snuggletex.SnuggleEngine;
import uk.ac.ed.ph.snuggletex.SnugglePackage;
import uk.ac.ed.ph.snuggletex.SnuggleSession;

import static uk.ac.ed.ph.snuggletex.definitions.Globals.ALL_MODES;

/// Owns the shared [SnuggleEngine], with the conversion table's command vocabulary registered on
/// top of SnuggleTeX's built-ins so the tokenizer accepts commands like `\textsuperscript` or
/// `\L` instead of flagging them as errors.
///
/// The engine is immutable after construction and safe to share; [SnuggleSession] is NOT
/// thread-safe, so every conversion gets a fresh session.
public final class SnuggleSupport {

    /// Commands the emitters handle specially but that appear in no table row.
    private static final Map<String, Integer> EXTRA_COMMANDS = Map.of(
            "textsuperscript", 1,
            "textsubscript", 1,
            "enquote", 1,
            "mkbibquote", 1,
            "mkbibemph", 1);

    private static final SnuggleEngine ENGINE = buildEngine();

    private SnuggleSupport() {
    }

    public static SnuggleSession createSession() {
        SnuggleSession session = ENGINE.createSession();
        // Collect all errors instead of aborting: unknown commands and stray _/^ are recoverable
        session.getConfiguration().setFailingFast(false);
        return session;
    }

    private static SnuggleEngine buildEngine() {
        SnuggleEngine engine = new SnuggleEngine();
        SnugglePackage snugglePackage = engine.getPackages().getFirst();
        // TreeMap for deterministic registration order
        Map<String, Integer> arities = new TreeMap<>(ConversionTable.commandArities());
        EXTRA_COMMANDS.forEach(arities::putIfAbsent);
        for (Map.Entry<String, Integer> entry : arities.entrySet()) {
            if (engine.getBuiltinCommandByTeXName(entry.getKey()) != null) {
                continue;
            }
            if (entry.getValue() == 0) {
                snugglePackage.addComplexCommand(entry.getKey(), false, 0, ALL_MODES, null, null, null);
            } else {
                snugglePackage.addComplexCommandSameArgMode(entry.getKey(), false, entry.getValue(), ALL_MODES, null, null);
            }
        }
        return engine;
    }
}
