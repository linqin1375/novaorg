package io.github.tangbailin.novaorg.knowledge;

import java.util.Locale;

/**
 * Text normalisation shared by the lexical (BM25) side of retrieval.
 *
 * <p>CJK text has no whitespace, so a whitespace tokenizer finds nothing. We emit both
 * whole ASCII words and CJK character bigrams, which is the cheap, dependency-free trick
 * that makes keyword search work for Chinese without pulling in a segmenter.
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    public static java.util.List<String> tokens(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                cjk.append(c);
                flushAscii(ascii, out);
            } else if (Character.isLetterOrDigit(c)) {
                ascii.append(c);
                flushCjk(cjk, out, false);
            } else {
                flushAscii(ascii, out);
                flushCjk(cjk, out, true);
            }
        }
        flushAscii(ascii, out);
        flushCjk(cjk, out, true);
        return out;
    }

    private static void flushAscii(StringBuilder sb, java.util.List<String> out) {
        if (sb.length() > 0) {
            out.add(sb.toString());
            sb.setLength(0);
        }
    }

    /** Emits unigrams at a run boundary and bigrams while the run continues. */
    private static void flushCjk(StringBuilder sb, java.util.List<String> out, boolean boundary) {
        if (sb.length() == 0) {
            return;
        }
        String run = sb.toString();
        for (int i = 0; i < run.length(); i++) {
            out.add(String.valueOf(run.charAt(i)));
            if (i + 1 < run.length()) {
                out.add(run.substring(i, i + 2));
            }
        }
        sb.setLength(0);
    }

    public static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
