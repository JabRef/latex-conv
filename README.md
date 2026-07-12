# latex-conv

Converts LaTeX to Unicode/HTML and back — [JabRef](https://github.com/JabRef/jabref)'s bibliography-field text conversion library.

Bibliographic fields (titles, author names, abstracts) are commonly stored as LaTeX: `Monta\~{n}a`, `Proceedings 5\textsuperscript{th}`, `$\pi$-calculus`. This library converts them for display, search, and round-trip editing:

| Direction | API | Example |
|---|---|---|
| LaTeX → Unicode | `LatexConv.toUnicode(latex)` | `Monta\~{n}a` → `Montaña`, `$\pi$` → `π`, `1\textsuperscript{st}` → `1ˢᵗ` |
| LaTeX → HTML | `LatexConv.toHtml(latex)` | `\emph{a}` → `<em>a</em>`, `x\textsubscript{i}` → `x<sub>i</sub>` |
| Unicode → LaTeX | `LatexConv.toLatex(text)` | `Montaña` → `Monta{\~{n}}a`-style, `π` → `$\pi$`, `4ᵗʰ` → `4\textsuperscript{th}` |

All conversions are total: they never throw and never return `null`. Input that cannot be parsed comes back NFC-normalized instead.

## Usage

```java
import org.jabref.latexconv.ConversionOptions;
import org.jabref.latexconv.LatexConv;

LatexConv.toUnicode("Mali\\'{n}ski");                    // "Maliński"
LatexConv.toHtml("\\textbf{Bold} $E=mc^2$");             // "<b>Bold</b> E=mc<sup>2</sup>"
LatexConv.toLatex("Puṇya");                              // "Pu{\\d{n}}ya"

// Options: script conversion, math handling, unknown commands
ConversionOptions options = ConversionOptions.defaults()
        .withConvertScripts(false)                       // keep \textsuperscript{...} verbatim so
                                                         // Unicode→LaTeX round-trips (JabRef #3644)
        .withMathMode(ConversionOptions.MathMode.PASSTHROUGH)  // leave $...$ spans untouched for a
                                                         // downstream math renderer (e.g. JLaTeXMath)
        .withUnknownCommandPolicy(ConversionOptions.UnknownCommandPolicy.UNWRAP);
LatexConv.toUnicode("BPEL\\textsuperscript{light}", options);  // "BPEL\\textsuperscript{light}"
```

### Rendering in html-to-node

The HTML output is a fragment that [html-to-node](https://github.com/JabRef/html-to-node) renders directly — `<sub>`/`<sup>` get proper baseline shifts, no changes needed there:

```java
Region node = HtmlToNode.render(LatexConv.toHtml("Proceedings 5\\textsuperscript{th} BPM"));
```

## What's supported

Parsing is done with [SnuggleTeX](https://github.com/davemckain/snuggletex); character data lives in a single bundled table (`conversion-table.tsv`, ~2000 rows) merged from Biber, tomtung/latex2unicode, and JabRef's conversion maps. Covered: accents and diacritics (both `\"{a}` and combining forms, `\d`/`\k`/dotless `\i`), Latin and Greek letters (`\L`, `\ss`, `\alpha`…), math symbols (SnuggleTeX's math table plus the conversion table), math alphabets (`\mathbb{A}` → 𝔸, `\textit{text}` → 𝑡𝑒𝑥𝑡 in Unicode output), super-/subscripts (text commands and math `^`/`_`, nested), biblatex commands (`\enquote`, `\mkbibquote`, `\mkbibemph`), and escaped specials (`\$`, `\&`, `\%`). Unknown commands are preserved exactly as written (configurable).

The category/flag format of the table is documented in its header comment. To regenerate it from the pinned upstream sources:

```bash
git submodule update --init          # tools/upstream/ — only needed for regeneration
java tools/GenerateTable.java        # rewrites the TSV + tools/CONFLICTS.md
```

Normal builds never need the submodules; CI's `table-up-to-date` job initializes them to verify the committed table matches the generator output.

## Requirements

- Java 24 or later
- No JavaFX or other UI dependencies — the library is headless

## Building

```bash
./gradlew build
```

## Relation to JabRef

This library replaces JabRef's dependency on the Scala library [latex2unicode](https://github.com/tomtung/latex2unicode) and its hand-maintained conversion maps, picking up the work started in [JabRef PR #6155](https://github.com/JabRef/jabref/pull/6155). The test suite carries JabRef's behavioral corpus, including regression cases for JabRef issues [#3644](https://github.com/JabRef/jabref/issues/3644), [#7291](https://github.com/JabRef/jabref/issues/7291), [#2458](https://github.com/JabRef/jabref/issues/2458), [#2498](https://github.com/JabRef/jabref/issues/2498), and [#2500](https://github.com/JabRef/jabref/issues/2500).

Known divergences from the historical JabRef formatters, all intentional: unmappable characters are kept instead of dropped, command emissions carry a single brace group (`{\aa}`, not `{{\aa}}`), Unicode script runs merge into one `\textsuperscript{...}`, and `\Delta` decodes to Δ (U+0394) rather than ∆ (U+2206).

## License

MIT — see [LICENSE](LICENSE). The bundled conversion table derives from third-party sources (Biber, Artistic-2.0; latex2unicode, Apache-2.0; JabRef, MIT) — see [NOTICE](NOTICE).
