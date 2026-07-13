# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-07-14

### Added

- LaTeX to Unicode conversion (`LatexConv.toUnicode`): accents and diacritics, Latin and Greek letters, math symbols and alphabets, super-/subscripts (including nested and combined `_{}^{}` scripts), biblatex commands (`\enquote`, `\mkbibquote`, `\mkbibemph`), `\text{...}`/`\operatorname{...}` in math, matched math brackets, `--`/`---` dash ligatures, and bare `%` as a literal character. The conversion is total: unparseable input comes back NFC-normalized instead of throwing.
- LaTeX to HTML conversion (`LatexConv.toHtml`): minimal fragments (`<i>`, `<em>`, `<b>`, `<code>`, `<sup>`, `<sub>`, typographic quotes) rendering directly in [html-to-node](https://github.com/JabRef/html-to-node).
- Unicode to LaTeX conversion (`LatexConv.toLatex`): precomposed and combining accent forms, math-mode wrapping, and Unicode script runs merged into a single `\textsuperscript{...}`/`\textsubscript{...}` (fixes the [JabRef/jabref#3644](https://github.com/JabRef/jabref/issues/3644) roundtrip).
- `ConversionOptions` with script-conversion toggle ([JabRef/jabref#3644](https://github.com/JabRef/jabref/issues/3644)), math mode (`UNICODE` or `PASSTHROUGH`), and unknown-command policy (`KEEP_COMMAND`, `UNWRAP`, `DROP`).
- Canonical conversion table (`conversion-table.tsv`, ~2000 rows) generated from [Biber](https://github.com/plk/biber), [tomtung/latex2unicode](https://github.com/tomtung/latex2unicode), and JabRef's conversion maps; CI verifies the committed table matches the generator output.

[Unreleased]: https://github.com/JabRef/latex-conv/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/JabRef/latex-conv/releases/tag/v0.1.0
