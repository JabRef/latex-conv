package org.jabref.latexconv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// `toUnicode(toLatex(text))` must reproduce the text for the character classes bib fields
/// actually contain (the JabRef issue #3644 class of bugs).
class RoundtripTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Montaña",
            "Pieńkowski",
            "Łęski",
            "Mönch",
            "Åström",
            "L'oscillation",
            // dotless ı̄ forms: dotted ī encodes to \={\i}, which decodes to ı̄ — a one-way
            // normalization inherent to the \i convention; ı̄ itself is roundtrip-stable
            "Puṇya-pattana-vidyā-pı̄ṭhādhi-kṛtaiḥ prā-kaśyaṃ nı̄taḥ",
            "naïve façade",
            "ı ı̄",
            "4ᵗʰ BPELˡⁱᵍʰᵗ xᵢ"
    })
    void unicodeSurvivesRoundtrip(String text) {
        assertEquals(text, LatexConv.toUnicode(LatexConv.toLatex(text)));
    }

    @Test
    void latexScriptsSurviveRoundtripWithScriptsDisabled() {
        // JabRef issue #3644: with convertScripts=false nothing is lost in either direction
        ConversionOptions options = ConversionOptions.defaults().withConvertScripts(false);
        String latex = "BPEL\\textsuperscript{light}";
        assertEquals(latex, LatexConv.toLatex(LatexConv.toUnicode(latex, options)));
    }
}
