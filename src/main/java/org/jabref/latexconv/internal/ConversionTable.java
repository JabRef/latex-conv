package org.jabref.latexconv.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// In-memory view of `conversion-table.tsv`, the generated LaTeX &lt;-&gt; Unicode conversion
/// table bundled as a module resource. See `tools/GenerateTable.java` and `tools/CONFLICTS.md` in
/// the source tree for how the table is generated and merged from its upstream sources.
///
/// The table is loaded lazily and exactly once, on first access to any lookup method.
public final class ConversionTable {

    private static final String RESOURCE_PATH = "/org/jabref/latexconv/conversion-table.tsv";

    private static final Pattern UNICODE_ESCAPE = Pattern.compile("~U\\+([0-9A-Fa-f]{4,6})");
    private static final Pattern SCRIPT_LATEX = Pattern.compile("\\\\text(?:super|sub)script\\{(.*)}");

    private record Row(String category, String unicode, String latex, List<String> flags) {
        boolean hasFlag(String flag) {
            return flags.contains(flag);
        }
    }

    private final List<Row> rows;
    private final Map<String, Row> decodable; // latex -> row, excluding encode-only rows
    private final Map<Integer, Row> encodePreferred; // codepoint -> canonical (non-alt, non-decode-only) row
    private final Map<String, Row> combiningByAccent; // latex -> diacritics/combining row
    private final Map<Integer, Integer> superscriptBaseToStyled;
    private final Map<Integer, Integer> superscriptStyledToBase;
    private final Map<Integer, Integer> subscriptBaseToStyled;
    private final Map<Integer, Integer> subscriptStyledToBase;

    private ConversionTable() {
        this.rows = load();

        Map<String, Row> decodableMap = new LinkedHashMap<>();
        Map<Integer, Row> encodePreferredMap = new LinkedHashMap<>();
        Map<String, Row> combiningMap = new LinkedHashMap<>();
        Map<Integer, Integer> supBaseToStyled = new LinkedHashMap<>();
        Map<Integer, Integer> supStyledToBase = new LinkedHashMap<>();
        Map<Integer, Integer> subBaseToStyled = new LinkedHashMap<>();
        Map<Integer, Integer> subStyledToBase = new LinkedHashMap<>();

        for (Row row : rows) {
            if (!row.hasFlag("encode-only") && !decodableMap.containsKey(row.latex())) {
                decodableMap.put(row.latex(), row);
            }
            if (!row.hasFlag("alt") && !row.hasFlag("decode-only")) {
                int cp = row.unicode().codePointAt(0);
                // Enforced by the generator (GenerateTable#verifyInvariant): at most one such row
                // per codepoint. putIfAbsent here is defensive, not a silent-override fallback.
                encodePreferredMap.putIfAbsent(cp, row);
            }
            if ("diacritics".equals(row.category()) && row.hasFlag("combining") && !row.hasFlag("encode-only")) {
                combiningMap.putIfAbsent(row.latex(), row);
            }
            if (row.hasFlag("script")) {
                Matcher m = SCRIPT_LATEX.matcher(row.latex());
                if (m.matches() && m.group(1).codePointCount(0, m.group(1).length()) == 1) {
                    int base = m.group(1).codePointAt(0);
                    int styled = row.unicode().codePointAt(0);
                    if ("superscripts".equals(row.category())) {
                        supBaseToStyled.putIfAbsent(base, styled);
                        supStyledToBase.putIfAbsent(styled, base);
                    } else if ("subscripts".equals(row.category())) {
                        subBaseToStyled.putIfAbsent(base, styled);
                        subStyledToBase.putIfAbsent(styled, base);
                    }
                }
            }
        }

        this.decodable = Map.copyOf(decodableMap);
        this.encodePreferred = Map.copyOf(encodePreferredMap);
        this.combiningByAccent = Map.copyOf(combiningMap);
        this.superscriptBaseToStyled = Map.copyOf(supBaseToStyled);
        this.superscriptStyledToBase = Map.copyOf(supStyledToBase);
        this.subscriptBaseToStyled = Map.copyOf(subBaseToStyled);
        this.subscriptStyledToBase = Map.copyOf(subStyledToBase);
    }

    private static final class Holder {
        private static final ConversionTable INSTANCE = new ConversionTable();
    }

    private static ConversionTable instance() {
        return Holder.INSTANCE;
    }

    /// Decodes a LaTeX command/spelling to its Unicode replacement text.
    ///
    /// Decode direction only: rows flagged `encode-only` are never returned here, even though
    /// they're valid `latex` spellings recognized during encoding. Returns an empty [Optional]
    /// when `latex` has no decode row (not merely "unknown" - it may be a spelling this table
    /// deliberately excludes from decode, e.g. an ambiguous shared spelling resolved in favor of
    /// a different codepoint; see `tools/CONFLICTS.md`).
    public static Optional<String> commandToUnicode(String latex) {
        Row row = instance().decodable.get(latex);
        return row == null ? Optional.empty() : Optional.of(row.unicode());
    }

    /// Looks up the combining Unicode mark produced by a bare LaTeX accent command (e.g. `\"`,
    /// `` \` ``, `\'`), for composing it onto a base character. Only considers `diacritics` rows
    /// flagged `combining`.
    public static Optional<String> combiningForAccent(String accentCommand) {
        Row row = instance().combiningByAccent.get(accentCommand);
        return row == null ? Optional.empty() : Optional.of(row.unicode());
    }

    /// Returns the styled superscript codepoint for a base codepoint (e.g. `'t'` -> `'ᵗ'`), if
    /// this table has one.
    public static OptionalInt superscriptFor(int codePoint) {
        Integer styled = instance().superscriptBaseToStyled.get(codePoint);
        return styled == null ? OptionalInt.empty() : OptionalInt.of(styled);
    }

    /// Reverse of [#superscriptFor(int)]: returns the base codepoint for a styled superscript
    /// codepoint.
    public static OptionalInt baseForSuperscript(int codePoint) {
        Integer base = instance().superscriptStyledToBase.get(codePoint);
        return base == null ? OptionalInt.empty() : OptionalInt.of(base);
    }

    /// Returns the styled subscript codepoint for a base codepoint, if this table has one.
    public static OptionalInt subscriptFor(int codePoint) {
        Integer styled = instance().subscriptBaseToStyled.get(codePoint);
        return styled == null ? OptionalInt.empty() : OptionalInt.of(styled);
    }

    /// Reverse of [#subscriptFor(int)]: returns the base codepoint for a styled subscript
    /// codepoint.
    public static OptionalInt baseForSubscript(int codePoint) {
        Integer base = instance().subscriptStyledToBase.get(codePoint);
        return base == null ? OptionalInt.empty() : OptionalInt.of(base);
    }

    /// Encodes a Unicode codepoint back to LaTeX.
    ///
    /// Encode direction only: returns the table's encode-preferred row for `codePoint` - the one
    /// row (per the generator's invariant, there is at most one) that is neither `alt` nor
    /// `decode-only`. Returns an empty [Optional] when no source offers an encode-preferred
    /// spelling for this codepoint (e.g. it was deliberately excluded via Biber's
    /// `encode_exclude`, or the codepoint simply isn't in any of the three sources).
    public static Optional<String> latexForCodePoint(int codePoint) {
        Row row = instance().encodePreferred.get(codePoint);
        return row == null ? Optional.empty() : Optional.of(row.latex());
    }

    /// Builds the base-codepoint -> styled-codepoint map for a math alphabet style command, e.g.
    /// `styleMap("mathbb")` maps `'A'` to the codepoint of 𝔸.
    ///
    /// Only `styles` rows whose `latex` matches `\<styleCommandName>{X}` for a single-codepoint
    /// `X` are considered (see `tools/CONFLICTS.md` for why `\text*` alias spellings are skipped
    /// entirely rather than merged in here). The returned map is unmodifiable.
    public static Map<Integer, Integer> styleMap(String styleCommandName) {
        Pattern pattern = Pattern.compile(Pattern.quote("\\" + styleCommandName + "{") + "(.)" + Pattern.quote("}"));
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (Row row : instance().rows) {
            if (!"styles".equals(row.category())) {
                continue;
            }
            Matcher m = pattern.matcher(row.latex());
            if (m.matches()) {
                map.put(m.group(1).codePointAt(0), row.unicode().codePointAt(0));
            }
        }
        return Map.copyOf(map);
    }

    /// Total number of rows loaded from the table, for test sanity checks.
    public static int size() {
        return instance().rows.size();
    }

    private static List<Row> load() {
        List<Row> result = new ArrayList<>();
        try (InputStream in = ConversionTable.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled resource: " + RESOURCE_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    String[] cols = line.split("\t", -1);
                    if (cols.length != 4) {
                        throw new IllegalStateException("Malformed conversion-table.tsv row: " + line);
                    }
                    String unicode = unescapeUnicode(cols[1]);
                    List<String> flags = cols[3].isEmpty() ? List.of() : List.of(cols[3].split(","));
                    result.add(new Row(cols[0], unicode, cols[2], flags));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + RESOURCE_PATH, e);
        }
        return List.copyOf(result);
    }

    private static String unescapeUnicode(String value) {
        Matcher m = UNICODE_ESCAPE.matcher(value);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(value, last, m.start());
            sb.appendCodePoint(Integer.parseInt(m.group(1), 16));
            last = m.end();
        }
        sb.append(value, last, value.length());
        return sb.toString();
    }
}
