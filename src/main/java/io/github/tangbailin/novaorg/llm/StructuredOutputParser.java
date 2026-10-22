package io.github.tangbailin.novaorg.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls the first JSON object/array out of a model response, tolerating ```json fences. */
public final class StructuredOutputParser {

    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL);

    private StructuredOutputParser() {
    }

    public static String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        Matcher fence = FENCE.matcher(text);
        if (fence.find()) {
            text = fence.group(1).trim();
        }
        int start = indexOfFirst(text, '{', '[');
        if (start < 0) {
            return "";
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private static int indexOfFirst(String text, char a, char b) {
        int ia = text.indexOf(a);
        int ib = text.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }
}
