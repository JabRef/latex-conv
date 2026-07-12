package org.jabref.latexconv.internal;

/// Signals input this library cannot convert (parse errors beyond the recoverable kinds). Callers
/// in the public API catch this and fall back to returning the normalized input, honoring the
/// never-throws contract.
public class UnsupportedLatexException extends RuntimeException {

    public UnsupportedLatexException(String message) {
        super(message);
    }
}
