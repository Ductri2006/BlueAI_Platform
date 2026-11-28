package com.blueai.rag.chat.service;

import java.util.Comparator;
import java.util.List;

/** Exact, assessment-only masking. This class does not establish evidence authorization. */
public final class ProtectedFacts {
    private ProtectedFacts() { }

    /**
     * Terms must come from validated, scoped evidence, never from model-declared identifiers.
     * Matching is literal and case-sensitive; original text and fact values are never rewritten.
     * Latin word boundaries prevent a short term from hiding part of an unrelated word while
     * allowing a product name to touch Chinese prose. Markdown has no special authority.
     */
    public static String maskAuthorizedTerms(String text, List<String> authorizedTerms) {
        if (text == null) return "";
        if (text.isEmpty() || authorizedTerms == null || authorizedTerms.isEmpty()) return text;
        char[] masked = text.toCharArray();
        for (String term : authorizedTerms.stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            for (int start = text.indexOf(term); start >= 0; start = text.indexOf(term, start + term.length())) {
                int end = start + term.length();
                if (start > 0 && isLatinWordPart(term.codePointAt(0))
                        && isLatinWordPart(text.codePointBefore(start))) continue;
                if (end < text.length() && isLatinWordPart(term.codePointBefore(term.length()))
                        && isLatinWordPart(text.codePointAt(end))) continue;
                java.util.Arrays.fill(masked, start, end, ' ');
            }
        }
        return new String(masked);
    }

    private static boolean isLatinWordPart(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                || Character.isDigit(codePoint) || codePoint == '_'
                || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }
}
