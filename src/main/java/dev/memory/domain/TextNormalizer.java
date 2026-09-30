package dev.memory.domain;

import java.text.Normalizer;
import java.util.Locale;

public final class TextNormalizer {
    private TextNormalizer() {}
    public static String normalize(String text) {
        // Retain punctuation and numbers: C, C++, C#, and negative numbers must stay distinct.
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
