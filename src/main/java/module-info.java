import org.jspecify.annotations.NullMarked;

/// Converts LaTeX to Unicode/HTML and back.
@NullMarked
module org.jabref.latexconv {
    requires transitive org.jspecify;
    // Not transitive: no SnuggleTeX types appear in the public API
    requires snuggletex.core;

    exports org.jabref.latexconv;
}
