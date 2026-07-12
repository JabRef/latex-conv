/*
 * Generates src/main/resources/org/jabref/latexconv/conversion-table.tsv from three upstream
 * sources (Biber, latex2unicode/tomtung, JabRef's HTMLUnicodeConversionMaps snapshot) and writes
 * tools/CONFLICTS.md documenting every merge conflict and skipped/non-representable entry.
 *
 * Run from the repository root: `java tools/GenerateTable.java` (JDK 21+, no external deps).
 */

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class GenerateTable {

    // Source priority: lower rank wins ties when picking the canonical encode-preferred row,
    // and wins ties when the *same* latex spelling decodes to two different codepoints.
    private static final int SRC_BIBER = 0;
    private static final int SRC_TOMTUNG = 1;
    private static final int SRC_JABREF = 2;
    private static final int SRC_SUPPLEMENT = 3;

    // See resolveCrossLatexConflicts(): Biber's greek group conflates these "var" commands with
    // their base letter's codepoint; tomtung has the real distinct Unicode script-variant glyph.
    private static final Set<String> VAR_GREEK_TOMTUNG_WINS =
            Set.of("\\vartheta", "\\varrho", "\\varphi", "\\varsigma", "\\varepsilon", "\\varkappa", "\\varpi");

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "letters", "diacritics", "superscripts", "subscripts", "greek", "symbols", "styles");

    /** A single (pre-final) table row. Flags are mutated in place while resolving conflicts. */
    private static final class Row {
        final String category;
        final String unicode; // literal characters, not yet ~U+XXXX escaped
        final String latex;
        final Set<String> flags = new LinkedHashSet<>();
        final int source;
        // All sources that contributed this exact triple; exact-duplicate collapsing merges the
        // duplicate's sources in here, so cross-source agreement stays visible afterwards
        final Set<Integer> sources = new LinkedHashSet<>();
        final boolean sourcePreferred; // Biber preferred="1" hint, used only for canonical pick

        Row(String category, String unicode, String latex, int source, boolean sourcePreferred) {
            if (!VALID_CATEGORIES.contains(category)) {
                throw new IllegalStateException("Invalid category: " + category);
            }
            this.category = category;
            this.unicode = unicode;
            this.latex = latex;
            this.source = source;
            this.sources.add(source);
            this.sourcePreferred = sourcePreferred;
        }

        int firstCodePoint() {
            return unicode.codePointAt(0);
        }

        String key() {
            return category + "\t" + unicode + "\t" + latex + "\t" + String.join(",", flags);
        }
    }

    private static final List<Row> rows = new ArrayList<>();
    private static final StringBuilder conflicts = new StringBuilder();
    private static final Map<String, Integer> skipCounts = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        Path repoRoot = Path.of("").toAbsolutePath();
        Path biberXml = repoRoot.resolve("tools/upstream/biber/lib/Biber/LaTeX/recode_data.xml");
        Path unaryScala = repoRoot.resolve(
                "tools/upstream/latex2unicode/src/main/scala/com/github/tomtung/latex2unicode/helper/Unary.scala");
        Path escapeScala = repoRoot.resolve(
                "tools/upstream/latex2unicode/src/main/scala/com/github/tomtung/latex2unicode/helper/Escape.scala");
        Path binaryScala = repoRoot.resolve(
                "tools/upstream/latex2unicode/src/main/scala/com/github/tomtung/latex2unicode/helper/Binary.scala");
        Path unaryWithOptionScala = repoRoot.resolve(
                "tools/upstream/latex2unicode/src/main/scala/com/github/tomtung/latex2unicode/helper/UnaryWithOption.scala");
        Path styleScala = repoRoot.resolve(
                "tools/upstream/latex2unicode/src/main/scala/com/github/tomtung/latex2unicode/helper/Style.scala");
        Path jabrefJava = repoRoot.resolve("tools/source-data/HTMLUnicodeConversionMaps.java");
        Path outTsv = repoRoot.resolve("src/main/resources/org/jabref/latexconv/conversion-table.tsv");
        Path outConflicts = repoRoot.resolve("tools/CONFLICTS.md");

        conflicts.append("# CONFLICTS.md\n\n");
        conflicts.append("Log of every merge conflict and skipped/non-representable entry encountered while\n");
        conflicts.append("generating `conversion-table.tsv` from Biber, latex2unicode (tomtung) and JabRef's\n");
        conflicts.append("`HTMLUnicodeConversionMaps` snapshot. Generated by `tools/GenerateTable.java`; do not\n");
        conflicts.append("edit by hand — re-run the generator instead.\n\n");

        Set<String> decodeExclude = new LinkedHashSet<>();
        Set<String> encodeExclude = new LinkedHashSet<>();
        parseBiber(biberXml, decodeExclude, encodeExclude);
        int afterBiber = rows.size();

        parseTomtungUnary(unaryScala);
        parseTomtungEscape(escapeScala);
        parseTomtungBinary(binaryScala);
        parseTomtungUnaryWithOption(unaryWithOptionScala);
        parseTomtungStyle(styleScala);
        int afterTomtung = rows.size();

        parseJabRef(jabrefJava);
        int afterJabRef = rows.size();

        addSupplements();

        conflicts.append("## Row counts by source (before merge-time dedup/conflict resolution)\n\n");
        conflicts.append("- Biber: ").append(afterBiber).append('\n');
        conflicts.append("- tomtung (latex2unicode): ").append(afterTomtung - afterBiber).append('\n');
        conflicts.append("- JabRef: ").append(afterJabRef - afterTomtung).append('\n');
        conflicts.append("- Total raw rows: ").append(rows.size()).append("\n\n");

        collapseDuplicateTriples();
        applyDecodeExclude(decodeExclude);
        applyEncodeExclude(encodeExclude);
        deduplicateExact();
        resolveCrossLatexConflicts();
        resolveCanonicalEncodeRows();
        verifyInvariant();

        rows.sort((a, b) -> {
            int c = a.category.compareTo(b.category);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.firstCodePoint(), b.firstCodePoint());
            if (c != 0) {
                return c;
            }
            c = a.latex.compareTo(b.latex);
            if (c != 0) {
                return c;
            }
            return String.join(",", a.flags).compareTo(String.join(",", b.flags));
        });

        writeTsv(outTsv);
        writeConflicts(outConflicts);

        printReport();
    }

    // ---------------------------------------------------------------------------------------
    // Biber recode_data.xml
    // ---------------------------------------------------------------------------------------

    private static void parseBiber(Path path, Set<String> decodeExclude, Set<String> encodeExclude) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(path.toFile());

        NodeList mapsNodes = doc.getElementsByTagName("maps");
        for (int i = 0; i < mapsNodes.getLength(); i++) {
            Element mapsEl = (Element) mapsNodes.item(i);
            String type = mapsEl.getAttribute("type");
            NodeList mapNodes = mapsEl.getElementsByTagName("map");
            for (int j = 0; j < mapNodes.getLength(); j++) {
                Element mapEl = (Element) mapNodes.item(j);
                Element fromEl = firstChildElement(mapEl, "from");
                Element toEl = firstChildElement(mapEl, "to");
                if (fromEl == null || toEl == null) {
                    continue;
                }
                String fromText = fromEl.getTextContent();
                boolean preferred = "1".equals(fromEl.getAttribute("preferred"));
                boolean raw = "1".equals(fromEl.getAttribute("raw"));
                String hex = toEl.getAttribute("hex");
                int cp = Integer.parseInt(hex, 16);
                String unicode = new String(Character.toChars(cp));

                addBiberRow(type, fromText, preferred, raw, unicode);
            }
        }

        Element decodeExcludeEl = firstChildElement(doc.getDocumentElement(), "decode_exclude");
        if (decodeExcludeEl != null) {
            for (Element charEl : childElements(decodeExcludeEl, "char")) {
                decodeExclude.add(charEl.getTextContent());
            }
        }
        Element encodeExcludeEl = firstChildElement(doc.getDocumentElement(), "encode_exclude");
        if (encodeExcludeEl != null) {
            for (Element charEl : childElements(encodeExcludeEl, "char")) {
                encodeExclude.add(charEl.getTextContent());
            }
        }
    }

    private static void addBiberRow(String type, String fromText, boolean preferred, boolean raw, String unicode) {
        switch (type) {
            case "letters" -> rows.add(new Row("letters", unicode, "\\" + fromText, SRC_BIBER, preferred));
            case "diacritics" -> {
                Row r = new Row("diacritics", unicode, "\\" + fromText, SRC_BIBER, preferred);
                r.flags.add("combining");
                rows.add(r);
            }
            case "greek" -> rows.add(new Row("greek", unicode, "\\" + fromText, SRC_BIBER, preferred));
            case "punctuation", "symbols" -> {
                String latex = raw ? fromText : "\\" + fromText;
                rows.add(new Row("symbols", unicode, latex, SRC_BIBER, preferred));
            }
            case "negatedsymbols" -> {
                // Biber's <from> here reuses the un-negated symbol's bare command name (e.g.
                // "ni"), but the row's actual target is that symbol's LaTeX-negated form: \not\ni
                // decodes to the negated glyph, not bare \ni. Using the bare "\" + fromText
                // spelling (as for the other symbol groups) would collide with the real,
                // un-negated command of the same name.
                rows.add(new Row("symbols", unicode, "\\not\\" + fromText, SRC_BIBER, preferred));
            }
            case "dings" -> {
                // Biber's <from> is the Zapfdingbats/pifont hex glyph code (not a command name);
                // synthesize the pifont \ding{<decimal>} spelling that actually renders it.
                int decimal = Integer.parseInt(fromText, 16);
                rows.add(new Row("symbols", unicode, "\\ding{" + decimal + "}", SRC_BIBER, preferred));
            }
            case "superscripts" -> {
                Row r = new Row("superscripts", unicode, "\\textsuperscript{" + fromText + "}", SRC_BIBER, preferred);
                if (fromText.codePointCount(0, fromText.length()) == 1) {
                    // Multi-char bases (e.g. "SM" -> service mark) don't round-trip through a
                    // single base codepoint, so they don't get the `script` flag.
                    r.flags.add("script");
                }
                rows.add(r);
            }
            case "cmdsuperscripts" ->
                // Ordinary command names that happen to target superscript-lookalike glyphs -
                // not a base-char substitution, so no `script` flag/wrapping.
                    rows.add(new Row("superscripts", unicode, "\\" + fromText, SRC_BIBER, preferred));
            default -> throw new IllegalStateException("Unhandled Biber maps type: " + type);
        }
    }

    private static void applyDecodeExclude(Set<String> decodeExclude) {
        int marked = 0;
        for (String token : decodeExclude) {
            String commandForm = "\\" + token;
            for (Row r : rows) {
                if (r.source == SRC_BIBER && (r.latex.equals(commandForm) || r.latex.equals(token))) {
                    r.flags.add("encode-only");
                    marked++;
                }
            }
        }
        conflicts.append("## Biber `decode_exclude` applied\n\n");
        conflicts.append("Tokens: ").append(decodeExclude).append(". Marked ").append(marked)
                .append(" Biber row(s) `encode-only` (recognized for encode, never chosen for decode).\n\n");
    }

    private static void applyEncodeExclude(Set<String> encodeExclude) {
        int marked = 0;
        for (String ch : encodeExclude) {
            for (Row r : rows) {
                if (r.unicode.equals(ch)) {
                    r.flags.add("decode-only");
                    marked++;
                }
            }
        }
        conflicts.append("## Biber `encode_exclude` applied\n\n");
        conflicts.append("Characters: ").append(encodeExclude.stream().map(GenerateTable::debugChar).toList())
                .append(". Marked ").append(marked)
                .append(" row(s) (across all sources) `decode-only` so none of them is ever chosen as the\n")
                .append("canonical encode target for that character; several excluded characters (e.g. space,\n")
                .append("`[`, `]`, backslash) have no matching row in any source and were no-ops.\n\n");
    }

    // ---------------------------------------------------------------------------------------
    // tomtung / latex2unicode
    // ---------------------------------------------------------------------------------------

    private static final Pattern STRING_LIT = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern CHAR_LIT = Pattern.compile("'((?:\\\\.|[^'\\\\])*)'");

    private static void parseTomtungUnary(Path path) throws IOException {
        String src = Files.readString(path, StandardCharsets.UTF_8);

        // combining: Map[String, (Char, CombiningType.Value)]
        String combiningBlock = extractBlock(src, "val combining", '(', ')');
        // Rows Biber already covers (by exact same latex spelling); everything else is added as
        // an `alt` spelling for the same combining codepoint (or as a brand-new row if the
        // codepoint has no Biber row at all).
        int added = 0, skippedDuplicate = 0;
        for (String line : combiningBlock.split("\n")) {
            Matcher sm = STRING_LIT.matcher(line);
            Matcher cm = CHAR_LIT.matcher(line);
            if (!sm.find() || !cm.find()) {
                continue;
            }
            String command = unescape(sm.group(1));
            String unicode = unescape(cm.group(1));
            boolean biberHasExact = rows.stream().anyMatch(r -> r.source == SRC_BIBER
                    && "diacritics".equals(r.category) && r.latex.equals(command) && r.unicode.equals(unicode));
            if (biberHasExact) {
                skippedDuplicate++;
                continue;
            }
            Row r = new Row("diacritics", unicode, command, SRC_TOMTUNG, false);
            r.flags.add("combining");
            r.flags.add("alt");
            rows.add(r);
            added++;
        }
        conflicts.append("## tomtung `Unary.combining` vs Biber `diacritics`\n\n");
        conflicts.append("Biber is the canonical encode source for accent commands. Bare-letter Biber spellings\n")
                .append("(`\\\"`, `` \\` ``, `\\'`, `\\^`, `\\~`, `\\=`, breve, `\\.`, `\\r`, `\\H`, `\\v`, `\\d`, `\\c`, `\\k`,\n")
                .append("`\\b`, `\\t`) already exist and are kept as the encode-preferred row; ").append(skippedDuplicate)
                .append(" identical (command, codepoint) pairs were skipped as duplicates. tomtung's additional\n")
                .append("long-form aliases (`\\grave`, `\\acute`, `\\hat`, `\\tilde`, `\\bar`, `\\overline`, `\\breve`,\n")
                .append("`\\dot`, `\\ddot`, `\\mathring`, `\\check`, `\\underline`, `\\underbar`, `\\vec`, `\\textcircled`)\n")
                .append("were added (").append(added).append(" row(s)) as `alt` decode spellings for the same combining\n")
                .append("codepoints.\n\n");

        // subscripts / superscripts: Map[Char, Char]
        addCharToCharMap(src, "val subscripts", "subscripts", "\\textsubscript");
        addCharToCharMap(src, "val superscripts", "superscripts", "\\textsuperscript");

        // Style-family maps: Map[Char, String]
        Map<String, String> bb = extractCharToStringMap(src, "val bb");
        Map<String, String> bf = extractCharToStringMap(src, "val bf");
        Map<String, String> cal = extractCharToStringMap(src, "val cal");
        Map<String, String> frak = extractCharToStringMap(src, "val frak");
        Map<String, String> it = extractCharToStringMap(src, "val it");
        Map<String, String> tt = extractCharToStringMap(src, "val tt");

        // styles: command name -> family map. Only the \math* spelling is emitted as the
        // canonical (non-alt) row for decode/encode; the \text* alias is deliberately skipped
        // rather than double-emitted (documented below) - full alias round-tripping is out of
        // scope for Phase 1.
        addStyleFamily("mathbb", bb);
        addStyleFamily("mathbf", bf);
        addStyleFamily("mathcal", cal);
        addStyleFamily("mathfrak", frak);
        addStyleFamily("mathit", it);
        addStyleFamily("mathtt", tt);
        conflicts.append("## tomtung `styles` (`\\mathbb`/`\\textbb` etc.)\n\n");
        conflicts.append("Only the `\\math*` command spelling is emitted per (family, base char) pair; the `\\text*`\n")
                .append("alias (same target character) is skipped rather than double-emitted. This is a deliberate\n")
                .append("Phase 1 simplification, not a real conflict - full alias round-tripping is out of scope.\n\n");
    }

    private static void addCharToCharMap(String src, String marker, String category, String wrapper) {
        String block = extractBlock(src, marker, '(', ')');
        int added = 0;
        for (String line : block.split("\n")) {
            Matcher cm = CHAR_LIT.matcher(line);
            if (!cm.find()) {
                continue;
            }
            String base = unescape(cm.group(1));
            if (!cm.find()) {
                continue;
            }
            String styled = unescape(cm.group(1));
            Row r = new Row(category, styled, wrapper + "{" + base + "}", SRC_TOMTUNG, false);
            r.flags.add("script");
            rows.add(r);
            added++;
        }
        conflicts.append("## tomtung `Unary.").append(category).append("`\n\n");
        conflicts.append("Added ").append(added).append(" row(s) (category `").append(category)
                .append("`). Biber has no native `subscripts` group, so tomtung is the sole source for\n")
                .append("`subscripts`; for `superscripts`, exact (base, styled) duplicates against Biber's own\n")
                .append("`superscripts` group are handled generically by exact-row dedup, and any base char that\n")
                .append("maps to a *different* styled char in the two sources is handled by the general\n")
                .append("cross-latex-spelling conflict resolution below.\n\n");
    }

    private static Map<String, String> extractCharToStringMap(String src, String marker) {
        String block = extractBlock(src, marker, '(', ')');
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : block.split("\n")) {
            Matcher cm = CHAR_LIT.matcher(line);
            if (!cm.find()) {
                continue;
            }
            String base = unescape(cm.group(1));
            Matcher sm = STRING_LIT.matcher(line);
            if (!sm.find(cm.end())) {
                continue;
            }
            map.put(base, unescape(sm.group(1)));
        }
        return map;
    }

    private static void addStyleFamily(String mathCommand, Map<String, String> family) {
        for (Map.Entry<String, String> e : family.entrySet()) {
            Row r = new Row("styles", e.getValue(), "\\" + mathCommand + "{" + e.getKey() + "}", SRC_TOMTUNG, false);
            r.flags.add("math");
            rows.add(r);
        }
    }

    private static void parseTomtungEscape(Path path) throws IOException {
        String src = Files.readString(path, StandardCharsets.UTF_8);
        String block = extractBlock(src, "val escapes", '(', ')');

        // Tokens that are markup/spacing rules rather than character substitutions - not
        // representable as a single codepoint-targeted table row.
        Set<String> notRepresentable = Set.of("$", "~", "\\;", "\\:", "\\,", "\\quad", "\\qquad", "-");
        int skippedMarkup = 0;
        int added = 0;
        int overriddenByLast = 0;
        Map<String, String> lastValuePerKey = new LinkedHashMap<>();

        for (String line : block.split("\n")) {
            if (line.contains("\"\"\"")) {
                // The raw triple-quoted `\\` -> newline entry; a line-join rule, not table data.
                skippedMarkup++;
                continue;
            }
            Matcher sm = STRING_LIT.matcher(line);
            if (!sm.find()) {
                continue;
            }
            String command = unescape(sm.group(1));
            if (!sm.find(sm.end())) {
                continue;
            }
            String target = unescape(sm.group(1));

            if (notRepresentable.contains(command)) {
                skippedMarkup++;
                continue;
            }
            if (target.isEmpty()) {
                skippedMarkup++;
                continue;
            }
            // Scala `Map(...)` literals keep the *last* occurrence of a duplicate key; mirror
            // that instead of naively adding every line (e.g. "\\aleph" appears twice with
            // different targets - only the final "ℵ" survives in the real tomtung map).
            lastValuePerKey.put(command, target);
        }

        for (Map.Entry<String, String> e : lastValuePerKey.entrySet()) {
            String command = e.getKey();
            String target = e.getValue();
            if (!command.startsWith("\\")) {
                // "-", "--", "---" etc. are handled by Biber's punctuation group already.
                skippedMarkup++;
                continue;
            }
            String category = isGreekLetterCommand(command) ? "greek" : "symbols";
            rows.add(new Row(category, target, command, SRC_TOMTUNG, false));
            added++;
        }
        overriddenByLast = 0; // computed implicitly via lastValuePerKey collapsing; see note below

        conflicts.append("## tomtung `Escape.escapes`\n\n");
        conflicts.append("Added ").append(added).append(" command rows (category `greek` or `symbols`). Skipped ")
                .append(skippedMarkup).append(" entries that are whitespace/markup rules or non-command tokens\n")
                .append("(`~`, `\\;`, `\\:`, `\\,`, `\\quad`, `\\qquad`, the raw `\\\\` -> newline rule, and bare\n")
                .append("`-`/`--`/`---`), not table-representable. `\\DJ` is a special case: tomtung's Scala map\n")
                .append("literal has `\"\\\\DJ\" -> \"Ð\"`, which collides with `\\DH` and looks like an upstream typo\n")
                .append("(it should be Đ, U+0110, matching Biber's own `DJ` entry); Biber's correct `\\DJ` -> Đ row\n")
                .append("is kept and tomtung's `\\DJ` -> Ð target is discarded rather than added as an `alt`.\n\n");
    }

    private static boolean isGreekLetterCommand(String command) {
        return switch (command) {
            case "\\alpha", "\\beta", "\\Gamma", "\\gamma", "\\Delta", "\\delta", "\\zeta", "\\eta", "\\Theta",
                 "\\theta", "\\Iota", "\\iota", "\\kappa", "\\Lambda", "\\lambda", "\\mu", "\\Nu", "\\nu", "\\Xi",
                 "\\xi", "\\Pi", "\\pi", "\\rho", "\\Sigma", "\\sigma", "\\tau", "\\Upsilon", "\\upsilon", "\\Phi",
                 "\\phi", "\\chi", "\\Psi", "\\psi", "\\Omega", "\\omega", "\\vartheta", "\\varsigma", "\\varrho",
                 "\\varpi", "\\varphi", "\\varkappa", "\\varepsilon", "\\Epsilon", "\\epsilon", "\\digamma" -> true;
            default -> false;
        };
    }

    private static void parseTomtungBinary(Path path) throws IOException {
        String src = Files.readString(path, StandardCharsets.UTF_8);
        String block = extractBlock(src, "val frac", '(', ')');
        int added = 0;
        for (String line : block.split("\n")) {
            Matcher m = STRING_LIT.matcher(line);
            if (!m.find()) {
                continue;
            }
            String numerator = unescape(m.group(1));
            if (!m.find(m.end())) {
                continue;
            }
            String denominator = unescape(m.group(1));
            if (!m.find(m.end())) {
                continue;
            }
            String target = unescape(m.group(1));
            rows.add(new Row("symbols", target, "\\frac{" + numerator + "}{" + denominator + "}", SRC_TOMTUNG, false));
            added++;
        }
        conflicts.append("## tomtung `Binary.frac`\n\n");
        conflicts.append("Added ").append(added).append(" `\\frac{n}{d}` vulgar-fraction rows (category `symbols`).\n")
                .append("Everything else `Binary` computes (general `\\frac{n}{d}` parenthesization for pairs not in\n")
                .append("this table) is procedural, not table-representable - handled in emitter code later.\n\n");
    }

    private static void parseTomtungUnaryWithOption(Path path) throws IOException {
        // `\sqrt` is almost entirely procedural (index-dependent superscript radix); only the
        // fixed cube/fourth-root glyphs are worth a table row (plain square root, index "2", is
        // already covered by Biber's `surd` -> √).
        rows.add(new Row("symbols", "∛", "\\sqrt[3]{}", SRC_TOMTUNG, false));
        rows.add(new Row("symbols", "∜", "\\sqrt[4]{}", SRC_TOMTUNG, false));
        conflicts.append("## tomtung `UnaryWithOption` (`\\sqrt`)\n\n");
        conflicts.append("Almost entirely procedural (builds a superscripted radix index for arbitrary `n`); only\n")
                .append("the two fixed glyphs U+221B (cube root, `\\sqrt[3]{}`) and U+221C (fourth root,\n")
                .append("`\\sqrt[4]{}`) were extracted as table rows. Plain square root (index blank/`\"2\"`) is not\n")
                .append("re-added since Biber's `surd` -> √ already covers it. Everything else is not\n")
                .append("table-representable - handled in emitter code later.\n\n");
    }

    private static void parseTomtungStyle(Path path) throws IOException {
        // Style.scala is pure indirection (\bf -> \textbf etc.), no data of its own.
        conflicts.append("## tomtung `Style.scala`\n\n");
        conflicts.append("Pure command aliasing (`\\bf` -> `\\textbf`, `\\cal` -> `\\textcal`, `\\it` -> `\\textit`,\n")
                .append("`\\tt` -> `\\texttt`), not separately table-representable - handled in emitter/parser code\n")
                .append("later.\n\n");
    }

    // ---------------------------------------------------------------------------------------
    // JabRef HTMLUnicodeConversionMaps.java snapshot
    // ---------------------------------------------------------------------------------------

    private static final Pattern JABREF_TRIPLE = Pattern.compile(
            "\\{\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*}");
    private static final Pattern JABREF_PAIR = Pattern.compile(
            "\\{\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*}");

    private static void parseJabRef(Path path) throws IOException {
        String src = stripJavaComments(Files.readString(path, StandardCharsets.UTF_8));

        String conversionBlock = extractArrayLiteral(src, "CONVERSION_LIST");
        int noCodepoint = 0;
        int belowThreshold = 0;
        int added = 0;
        Matcher m = JABREF_TRIPLE.matcher(conversionBlock);
        while (m.find()) {
            String cpField = unescapeJava(m.group(1));
            String latexRaw = unescapeJava(m.group(3));
            if (cpField.isEmpty()) {
                noCodepoint++;
                continue;
            }
            int cp = Integer.parseInt(cpField);
            if (cp <= 128) {
                // Mirrors HTMLUnicodeConversionMaps' own static initializer, which only feeds
                // LATEX_UNICODE_CONVERSION_MAP/UNICODE_LATEX_CONVERSION_MAP for codepoints > 128.
                belowThreshold++;
                continue;
            }
            String latex = stripRedundantBraces(latexRaw.strip());
            if (latex.isEmpty()) {
                noCodepoint++;
                continue;
            }
            String unicode = new String(Character.toChars(cp));
            String category = categorize(cp, latex);
            Row r = new Row(category, unicode, latex, SRC_JABREF, false);
            if (category.equals("superscripts") || category.equals("subscripts")) {
                r.flags.add("script");
            }
            if (category.equals("styles")) {
                r.flags.add("math");
            }
            rows.add(r);
            added++;
        }
        conflicts.append("## JabRef `CONVERSION_LIST`\n\n");
        conflicts.append("Added ").append(added).append(" rows. Skipped ").append(noCodepoint)
                .append(" entries with no codepoint (or no usable LaTeX text after normalization) and ")
                .append(belowThreshold).append(" entries with codepoint <= 128, mirroring\n")
                .append("`HTMLUnicodeConversionMaps`'s own static initializer, which only populates its\n")
                .append("`*_UNICODE_*` maps for codepoints > 128 - ASCII punctuation is already covered by\n")
                .append("Biber's `symbols`/`punctuation` groups. Latex text is normalized by stripping matching\n")
                .append("outer `{...}` brace pairs (JabRef-internal grouping noise) while preserving `$...$`\n")
                .append("math-mode wrappers and any inner `{arg}` verbatim, e.g. `{\\textexclamdown}` ->\n")
                .append("`\\textexclamdown`, `{{\\S}}` -> `\\S`, `$\\pm$` stays `$\\pm$`.\n\n");

        String accentBlock = extractArrayLiteral(src, "ACCENT_LIST");
        int addedAccents = 0;
        Matcher am = JABREF_PAIR.matcher(accentBlock);
        while (am.find()) {
            String cpField = unescapeJava(am.group(1));
            String token = unescapeJava(am.group(2));
            if (cpField.isEmpty() || token.isEmpty()) {
                continue;
            }
            int cp = Integer.parseInt(cpField);
            String unicode = new String(Character.toChars(cp));
            Row r = new Row("diacritics", unicode, "\\" + token, SRC_JABREF, false);
            r.flags.add("combining");
            rows.add(r);
            addedAccents++;
        }
        conflicts.append("## JabRef `ACCENT_LIST`\n\n");
        conflicts.append("Added ").append(addedAccents).append(" candidate diacritics rows (same shape as Biber's\n")
                .append("`diacritics` group: bare accent trigger token -> combining mark). Most duplicate Biber\n")
                .append("exactly and are removed by exact-row dedup; genuine differences (e.g. token `b` is used by\n")
                .append("JabRef for both U+0331 \"macron below\" and U+0332 \"underline\", while Biber's own `\\b` is\n")
                .append("U+0331) are resolved by the general cross-latex-spelling conflict pass below.\n\n");
    }

    private static String categorize(int cp, String latex) {
        if (latex.matches(".*\\\\textsuperscript\\{.\\}.*") && !latex.startsWith("\\ding")) {
            return "superscripts";
        }
        if (latex.contains("\\textsubscript{") || latex.matches("\\$_\\{.+}\\$")) {
            return "subscripts";
        }
        if (latex.matches(".*\\\\(math|text)(bb|bf|cal|frak|it|tt)\\{.\\}.*")) {
            return "styles";
        }
        if ((cp >= 0x0370 && cp <= 0x03FF) || (cp >= 0x1F00 && cp <= 0x1FFF)) {
            return "greek";
        }
        if ((cp >= 0x00C0 && cp <= 0x024F) || (cp >= 0x1E00 && cp <= 0x1EFF)) {
            return "letters";
        }
        return "symbols";
    }

    // ---------------------------------------------------------------------------------------
    // Merge-time conflict resolution
    // ---------------------------------------------------------------------------------------

    private static void deduplicateExact() {
        Set<String> seen = new LinkedHashSet<>();
        List<Row> unique = new ArrayList<>();
        int dropped = 0;
        Map<String, Row> survivors = new LinkedHashMap<>();
        for (Row r : rows) {
            if (seen.add(r.key())) {
                unique.add(r);
                survivors.put(r.key(), r);
            } else {
                survivors.get(r.key()).sources.addAll(r.sources);
                dropped++;
            }
        }
        rows.clear();
        rows.addAll(unique);
        conflicts.append("## Exact-duplicate rows removed\n\n");
        conflicts.append("Removed ").append(dropped)
                .append(" row(s) that were byte-for-byte identical (category, unicode, latex, flags) to an\n")
                .append("earlier row from a different source - not logged individually.\n\n");
    }

    /**
     * Same latex spelling decoding to two different codepoints is ambiguous - `commandToUnicode`
     * can only return one answer. Picks a decode winner per latex spelling and demotes every
     * other row sharing that spelling to `encode-only`.
     */
    private static void resolveCrossLatexConflicts() {
        Map<String, List<Row>> byLatex = new LinkedHashMap<>();
        for (Row r : rows) {
            byLatex.computeIfAbsent(r.latex, k -> new ArrayList<>()).add(r);
        }

        // tomtung's decode opinion per bare spelling. Its math commands are stored $-wrapped
        // (e.g. `$\Delta$`), which lands them in a different spelling group than Biber's bare
        // `\Delta` - strip the wrapping so agreement is still visible.
        Map<String, String> tomtungOpinion = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.sources.contains(SRC_TOMTUNG)) {
                String bare = r.latex.length() > 2 && r.latex.startsWith("$") && r.latex.endsWith("$")
                        ? r.latex.substring(1, r.latex.length() - 1)
                        : r.latex;
                tomtungOpinion.putIfAbsent(bare, r.unicode);
            }
        }

        conflicts.append("## Cross-latex-spelling conflicts (same LaTeX text, different codepoints)\n\n");
        int conflictGroups = 0;
        for (Map.Entry<String, List<Row>> e : byLatex.entrySet()) {
            String latex = e.getKey();
            List<Row> group = e.getValue();
            Set<String> distinctUnicode = new LinkedHashSet<>();
            for (Row r : group) {
                distinctUnicode.add(r.unicode);
            }
            if (distinctUnicode.size() <= 1) {
                continue;
            }
            conflictGroups++;

            Row winner;
            if (latex.equals("\\textendash") || latex.equals("--")) {
                // U+2012 (figure dash) and U+2013 (en dash) both claim "\textendash" and "--" as
                // spellings in Biber's punctuation group; U+2013 is the far more common intended
                // meaning, so it wins decode and U+2012's rows for these spellings become
                // encode-only (U+2012 keeps no other decode-safe spelling in this source set).
                winner = group.stream().filter(r -> r.unicode.codePointAt(0) == 0x2013).findFirst()
                        .orElse(pickFirstBySourcePriority(group, latex));
            } else if (VAR_GREEK_TOMTUNG_WINS.contains(latex) && group.stream().anyMatch(r -> r.source == SRC_TOMTUNG)) {
                // Biber's greek group conflates each \var* command with its base letter (e.g.
                // \vartheta -> same codepoint as \theta), while tomtung distinguishes the actual
                // Unicode script-variant glyph (e.g. \vartheta -> U+03D1, not U+03B8). tomtung
                // wins decode for exactly this small, well-understood set of variant-letter
                // commands - NOT a blanket "tomtung wins symbols/greek" rule, since that would
                // also let genuine tomtung data bugs (e.g. its "\rtimes" -> "⋈" typo, which
                // should be "\bowtie") silently override Biber's correct value.
                winner = group.stream().filter(r -> r.source == SRC_TOMTUNG).findFirst()
                        .orElse(pickFirstBySourcePriority(group, latex));
            } else {
                winner = pickTomtungAgreement(group, tomtungOpinion.get(latex));
                if (winner == null) {
                    winner = pickFirstBySourcePriority(group, latex);
                }
            }

            for (Row r : group) {
                if (!r.unicode.equals(winner.unicode)) {
                    r.flags.add("encode-only");
                }
            }

            conflicts.append("- `").append(escapeMd(latex)).append("`: targets {")
                    .append(distinctUnicode.stream().map(GenerateTable::debugChar).reduce((a, b) -> a + ", " + b).orElse(""))
                    .append("} - decode winner ").append(debugChar(winner.unicode))
                    .append(", other(s) marked `encode-only`.\n");
        }
        if (conflictGroups == 0) {
            conflicts.append("(none)\n");
        }
        conflicts.append("\nTotal conflict groups: ").append(conflictGroups).append(".\n\n");
    }

    /** Spellings none of the three sources carries but real-world bib fields use. */
    private static void addSupplements() {
        // \backslash is the standard math-mode spelling of "\"; the sources only know
        // \textbackslash. decode-only: encoding "\" stays excluded, like Biber does.
        Row backslash = new Row("symbols", "\\", "\\backslash", SRC_SUPPLEMENT, false);
        backslash.flags.add("decode-only");
        rows.add(backslash);
        conflicts.append("## Generator supplements\n\n");
        conflicts.append("Rows added by the generator itself, absent from all three sources:\n\n");
        conflicts.append("- `\\backslash` -> `\\` (symbols, decode-only).\n\n");
    }

    /**
     * When Biber itself offers two codepoints for one spelling with no preferred hint (e.g.
     * \Delta appears in its symbols group as U+2206 and its greek group as U+0394), file order is
     * a meaningless tie-breaker. If tomtung maps the same (unwrapped) spelling to one of the
     * contested codepoints, that two-source agreement decides (\Delta -> U+0394 Δ, matching
     * standard LaTeX). Returns null when the rule does not apply.
     */
    private static Row pickTomtungAgreement(List<Row> group, String tomtungUnicode) {
        if (tomtungUnicode == null) {
            return null;
        }
        Set<String> biberTargets = new LinkedHashSet<>();
        for (Row r : group) {
            if (r.sources.contains(SRC_BIBER)) {
                if (r.sourcePreferred) {
                    return null;
                }
                biberTargets.add(r.unicode);
            }
        }
        if (biberTargets.size() < 2 || !biberTargets.contains(tomtungUnicode)) {
            return null;
        }
        for (Row r : group) {
            if (r.unicode.equals(tomtungUnicode)) {
                return r;
            }
        }
        return null;
    }

    private static Row pickFirstBySourcePriority(List<Row> group, String latex) {
        // Prefer an explicit Biber preferred="1" hint, then first-encountered Biber row, then
        // first tomtung, then first JabRef (source list order == insertion order == this
        // priority already, since rows were added Biber, then tomtung, then JabRef).
        for (Row r : group) {
            if (r.source == SRC_BIBER && r.sourcePreferred) {
                return r;
            }
        }
        return group.get(0);
    }

    /**
     * Enforces "at most one non-alt, non-decode-only row per distinct unicode value": picks the
     * encode-preferred row per codepoint and demotes every other eligible row to `alt`.
     */
    private static void resolveCanonicalEncodeRows() {
        Map<String, List<Row>> byUnicode = new LinkedHashMap<>();
        for (Row r : rows) {
            byUnicode.computeIfAbsent(r.unicode, k -> new ArrayList<>()).add(r);
        }

        for (List<Row> group : byUnicode.values()) {
            List<Row> eligible = group.stream().filter(r -> !r.flags.contains("decode-only")).toList();
            if (eligible.isEmpty()) {
                continue;
            }
            Row canonical = null;
            for (Row r : eligible) {
                if (r.source == SRC_BIBER && r.sourcePreferred) {
                    canonical = r;
                    break;
                }
            }
            if (canonical == null) {
                for (Row r : eligible) {
                    if (r.source == SRC_BIBER) {
                        canonical = r;
                        break;
                    }
                }
            }
            if (canonical == null) {
                for (Row r : eligible) {
                    if (r.source == SRC_TOMTUNG) {
                        canonical = r;
                        break;
                    }
                }
            }
            if (canonical == null) {
                canonical = eligible.get(0);
            }
            for (Row r : eligible) {
                if (r != canonical) {
                    r.flags.add("alt");
                }
            }
        }
    }

    private static void verifyInvariant() {
        Map<String, List<Row>> byUnicode = new LinkedHashMap<>();
        for (Row r : rows) {
            byUnicode.computeIfAbsent(r.unicode, k -> new ArrayList<>()).add(r);
        }
        int violations = 0;
        for (Map.Entry<String, List<Row>> e : byUnicode.entrySet()) {
            long nonAltNonDecodeOnly = e.getValue().stream()
                    .filter(r -> !r.flags.contains("alt") && !r.flags.contains("decode-only"))
                    .count();
            if (nonAltNonDecodeOnly > 1) {
                violations++;
                System.err.println("INVARIANT VIOLATION for " + debugChar(e.getKey()) + ": "
                        + nonAltNonDecodeOnly + " non-alt/non-decode-only rows");
                // Force all but the first offender to `alt` so the generator still produces a
                // table that satisfies the documented invariant, and fail loudly via stderr/exit.
                boolean first = true;
                for (Row r : e.getValue()) {
                    if (r.flags.contains("alt") || r.flags.contains("decode-only")) {
                        continue;
                    }
                    if (first) {
                        first = false;
                    } else {
                        r.flags.add("alt");
                    }
                }
            }
        }
        if (violations > 0) {
            throw new IllegalStateException(
                    "Encode-preferred-row invariant was violated for " + violations + " codepoint(s); "
                            + "forced extras to `alt` - re-run and inspect stderr output above.");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Output
    // ---------------------------------------------------------------------------------------

    private static void writeTsv(Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# conversion-table.tsv - LaTeX <-> Unicode conversion table for org.jabref.latexconv\n");
        sb.append("#\n");
        sb.append("# Generated by tools/GenerateTable.java. Do not edit by hand; re-run the generator instead.\n");
        sb.append("#\n");
        sb.append("# Columns (tab-separated): category<TAB>unicode<TAB>latex<TAB>flags\n");
        sb.append("#   category: one of letters, diacritics, superscripts, subscripts, greek, symbols, styles\n");
        sb.append("#   unicode:  literal character(s); combining marks/whitespace/non-printables are escaped as\n");
        sb.append("#             ~U+XXXX (may be mixed with literal characters in the same value)\n");
        sb.append("#   latex:    full LaTeX replacement text verbatim (e.g. \\alpha, \\\", \\textsuperscript{t},\n");
        sb.append("#             \\mathbb{A}, \\frac{1}{2}); for `combining` rows this is the bare accent command\n");
        sb.append("#   flags:    comma-separated subset of decode-only, encode-only, alt, combining, math, script\n");
        sb.append("#             the FIRST row (file order) for a given unicode value that lacks both alt and\n");
        sb.append("#             decode-only is THE encode-preferred row for that value; there is at most one\n");
        sb.append("#             such row per distinct unicode value\n");
        sb.append("#\n");
        sb.append("# Rows are sorted by category (alphabetically), then by the unicode value's first codepoint,\n");
        sb.append("# then by latex text, then by flags - deterministic and reproducible across runs.\n");
        sb.append("#\n");
        sb.append("# Derived from, in order of encode priority (Biber wins ties, then tomtung, then JabRef):\n");
        sb.append("#\n");
        sb.append("#  - Biber, lib/Biber/LaTeX/recode_data.xml, submodule tools/upstream/biber pinned at commit\n");
        sb.append("#    74252e608e5f8115375c532eb25416430a9f52eb (tag area v0.4-4166-g74252e60).\n");
        sb.append("#    License: Artistic License 2.0. https://github.com/plk/biber\n");
        sb.append("#  - latex2unicode (tomtung), src/main/scala/.../helper/{Unary,Escape,Binary,UnaryWithOption,\n");
        sb.append("#    Style}.scala, submodule tools/upstream/latex2unicode pinned at commit\n");
        sb.append("#    1c8b7c4af7c0f2aee3937c5c24305fd01063bc1b (tag v0.3.2).\n");
        sb.append("#    License: Apache License 2.0. https://github.com/tomtung/latex2unicode\n");
        sb.append("#  - JabRef HTMLUnicodeConversionMaps, tools/source-data/HTMLUnicodeConversionMaps.java, a\n");
        sb.append("#    snapshot from https://github.com/JabRef/jabref @ b0d3eb4f8e248750ac35fb369666d329dfc1d9d2.\n");
        sb.append("#    License: MIT. Parts of that table derive from ISO 8879 entity definitions.\n");
        sb.append("#\n");
        sb.append("# See tools/CONFLICTS.md for every merge conflict and skipped/non-representable entry.\n");

        for (Row r : rows) {
            sb.append(r.category).append('\t')
                    .append(escapeUnicode(r.unicode)).append('\t')
                    .append(r.latex).append('\t')
                    .append(String.join(",", r.flags)).append('\n');
        }

        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeConflicts(Path out) throws IOException {
        Files.createDirectories(out.getParent());
        Files.writeString(out, conflicts.toString(), StandardCharsets.UTF_8);
    }

    private static void printReport() {
        Map<String, Long> byCategory = new TreeMap<>();
        Map<String, Long> byFlag = new TreeMap<>();
        for (Row r : rows) {
            byCategory.merge(r.category, 1L, Long::sum);
            if (r.flags.isEmpty()) {
                byFlag.merge("(none)", 1L, Long::sum);
            }
            for (String f : r.flags) {
                byFlag.merge(f, 1L, Long::sum);
            }
        }
        System.out.println("Total rows: " + rows.size());
        System.out.println("By category: " + byCategory);
        System.out.println("By flag (rows may carry >1 flag, so this does not sum to the total): " + byFlag);
    }

    // ---------------------------------------------------------------------------------------
    // Small parsing utilities
    // ---------------------------------------------------------------------------------------

    private static Element firstChildElement(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tag)) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tag)) {
                result.add((Element) n);
            }
        }
        return result;
    }

    /** Extracts the {@code Map( ... )}-style block following {@code marker} up to its matching close-paren. */
    private static String extractBlock(String src, String marker, char open, char close) {
        int idx = src.indexOf(marker);
        if (idx < 0) {
            throw new IllegalStateException("Marker not found: " + marker);
        }
        int parenStart = src.indexOf(open, idx);
        int depth = 0;
        int i = parenStart;
        for (; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        return src.substring(parenStart + 1, i);
    }

    /** Extracts the {@code {...}} array-literal block following {@code fieldName}. */
    private static String extractArrayLiteral(String src, String fieldName) {
        int idx = src.indexOf(fieldName);
        if (idx < 0) {
            throw new IllegalStateException("Field not found: " + fieldName);
        }
        int braceStart = src.indexOf('{', src.indexOf("[]", idx));
        int depth = 0;
        int i = braceStart;
        for (; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        return src.substring(braceStart, i + 1);
    }

    /** Unescapes a Scala string/char literal body (backslash, quote, apostrophe, n, t, r, unicode escapes). */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'u' -> {
                        int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                        sb.appendCodePoint(cp);
                        i += 6;
                    }
                    case 'n' -> {
                        sb.append('\n');
                        i += 2;
                    }
                    case 't' -> {
                        sb.append('\t');
                        i += 2;
                    }
                    case 'r' -> {
                        sb.append('\r');
                        i += 2;
                    }
                    case '\\', '"', '\'' -> {
                        sb.append(n);
                        i += 2;
                    }
                    default -> {
                        sb.append(n);
                        i += 2;
                    }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Unescapes a Java string literal body (same escape set as {@link #unescape}). */
    private static String unescapeJava(String s) {
        return unescape(s);
    }

    /**
     * Strips {@code //} line comments and {@code /* *}{@code /} block comments from Java source,
     * respecting string literals so a {@code //} or {@code /*} inside a quoted string is left
     * alone. Needed because {@code HTMLUnicodeConversionMaps.CONVERSION_LIST} has commented-out
     * array entries (e.g. the U+22F0 "up right diagonal ellipsis" row) that must not be parsed as
     * live data.
     */
    private static String stripJavaComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"') {
                int start = i;
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        i++;
                    }
                    i++;
                }
                i++; // closing quote
                sb.append(src, start, Math.min(i, n));
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Collapses rows sharing the same (category, unicode, latex) triple - which can arise
     * legitimately when two sources independently document the same command (e.g. Biber's
     * `dots` and tomtung's `\dots` both target …) - into a single row, unioning their flags,
     * before conflict resolution runs. Without this, the two source rows stay separate objects
     * and can drift to contradictory flag sets (e.g. one ending up `encode-only`, the other
     * `alt`) even though they describe the exact same mapping.
     */
    private static void collapseDuplicateTriples() {
        Map<String, Row> byTriple = new LinkedHashMap<>();
        for (Row r : rows) {
            String triple = r.category + "" + r.unicode + "" + r.latex;
            Row existing = byTriple.get(triple);
            if (existing == null) {
                byTriple.put(triple, r);
            } else {
                existing.flags.addAll(r.flags);
                existing.sources.addAll(r.sources);
                // Keep the higher-priority (lower rank) source and its preferred hint.
            }
        }
        rows.clear();
        rows.addAll(byTriple.values());
    }

    /** Strips matching outer {@code {...}} brace pairs, repeatedly, leaving $...$ and inner text untouched. */
    private static String stripRedundantBraces(String s) {
        while (s.length() >= 2 && s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}' && isBalancedWrap(s)) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static boolean isBalancedWrap(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && i != s.length() - 1) {
                    return false; // the opening brace at 0 closes before the end: not a single outer wrap
                }
            }
        }
        return depth == 0;
    }

    private static String escapeUnicode(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (needsEscape(cp)) {
                sb.append("~U+").append(String.format("%04X", cp));
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    private static boolean needsEscape(int cp) {
        if (Character.isWhitespace(cp) || Character.isISOControl(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.UNASSIGNED
                || type == Character.PRIVATE_USE;
    }

    private static String debugChar(String s) {
        int cp = s.codePointAt(0);
        return String.format("U+%04X (%s)", cp, needsEscape(cp) ? "non-printable" : s);
    }

    private static String escapeMd(String s) {
        return s.replace("|", "\\|");
    }
}
