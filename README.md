# latex-conv

Converts LaTeX to Unicode/HTML and back — [JabRef](https://github.com/JabRef/jabref)'s bibliography-field text conversion library.

> **Status: work in progress.** The API below is the target shape; conversions are being implemented incrementally.

## What it does

Bibliographic fields (titles, author names, abstracts) are commonly stored as LaTeX (`Monta\~{n}a`, `\textsuperscript{th}`, `$\pi$`). This library converts them:

- **LaTeX → Unicode** — plain text for tables, search indexes, and citation keys (`Montaña`, `ᵗʰ`, `π`)
- **LaTeX → HTML** — a minimal HTML fragment (`<sub>`, `<sup>`, `<em>`, `<b>`, Unicode characters) for rich display, e.g. via [html-to-node](https://github.com/JabRef/html-to-node)
- **Unicode → LaTeX** — the reverse direction, roundtrip-safe

Parsing is done with [SnuggleTeX](https://github.com/davemckain/snuggletex); the character mappings derive from [Biber](https://github.com/plk/biber)'s recode data, JabRef's conversion tables, and [latex2unicode](https://github.com/tomtung/latex2unicode) (see `NOTICE`).

## Usage

```java
import org.jabref.latexconv.LatexConv;

String plain = LatexConv.toUnicode("Monta\\~{n}a");   // "Montaña"
```

## Requirements

- Java 24 or later
- No JavaFX or other UI dependencies — the library is headless

## Building

```bash
./gradlew build
```

## License

MIT — see [LICENSE](LICENSE). Bundled conversion data is derived from third-party sources; see [NOTICE](NOTICE).
