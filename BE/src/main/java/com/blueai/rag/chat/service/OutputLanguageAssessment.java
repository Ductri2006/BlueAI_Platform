package com.blueai.rag.chat.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline prose assessment, independent of grounding validation and paid repair decisions. */
public final class OutputLanguageAssessment {
    public enum Result { MATCH, MISMATCH, UNCERTAIN }

    private static final Pattern LATIN_PROSE = Pattern.compile(
            "[\\p{IsLatin}\\p{M}]+(?:[\\h'’\\-]+[\\p{IsLatin}\\p{M}]+)*");
    private static final Pattern HAN_PROSE = Pattern.compile("\\p{IsHan}+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern TECHNICAL_TERMS = Pattern.compile(
            "(?i)(?<![\\p{IsLatin}\\p{N}_])(?:esim|sim|apn|qr|ios|android|iphone|ipad|gb|mb|tb|usd|vnd|jpy|wifi|wi-fi|5g|4g)(?![\\p{IsLatin}\\p{N}_])");
    // These are prose cues, not language votes or a Latin/Han character quota.
    // A statistical guess about a bare brand name is not sufficient to reject an answer.
    private static final Pattern ENGLISH_PROSE = Pattern.compile(
            "\\b(?:please|you|your|we|our|i|my|this|that|these|those|the|is|are|was|were|has|have|cannot|not)\\b"
                    + "|^(?:enable|disable|contact|open|select|choose|install|activate|check|turn|set|restart|scan|enter|provide|visit)\\b"
                    + "|\\bno (?:internet|network|signal)\\b");
    private static final Pattern VIETNAMESE_PROSE = Pattern.compile(
            "\\b(?:ban|hay|vui long|chung toi|cua|khong|duoc|can|muon|trong|cho)\\b"
                    + "|^(?:bat|tat|chon|mo|kiem tra|nhap|quet|cung cap|lien he)\\b");
    private static final Pattern VIETNAMESE_GUIDANCE = Pattern.compile(
            "\\b(?:khong co mang|khong co song|che do may bay|chuyen vung du lieu|cai dat|du lieu di dong|esim du lich|bat duong day nay)\\b");
    private static final Set<String> SHORT_CHINESE = Set.of(
            "好", "好的", "是", "不是", "可以", "不可以", "谢谢", "謝謝", "抱歉", "已取消");

    private OutputLanguageAssessment() { }

    /**
     * authorizedTerms are exact names/fact spans supplied by the caller from validated tool or
     * RAG evidence. Quotes, backticks and model-declared identifiers never hide prose. MATCH
     * establishes language only, not fact correctness or source authorization. UNCERTAIN must
     * not be treated as a proven mismatch or trigger an implicit AI call.
     */
    public static Result assess(String text, ReplyLanguagePolicy.State language, List<String> authorizedTerms) {
        if (text == null || text.isBlank() || language == null) return Result.UNCERTAIN;
        String prose = TECHNICAL_TERMS.matcher(ProtectedFacts.maskAuthorizedTerms(text, authorizedTerms)).replaceAll(" ");
        if (prose.codePoints().anyMatch(cp -> switch (Character.UnicodeScript.of(cp)) {
            case HIRAGANA, KATAKANA, HANGUL -> true;
            default -> false;
        })) return Result.MISMATCH;

        boolean matched = false;
        boolean uncertain = false;
        var latin = LATIN_PROSE.matcher(prose);
        while (latin.find()) {
            String run = latin.group().trim();
            // Assess every bounded window, including prose after a long identifier or list.
            for (int start = 0; start < run.length();) {
                int end = Math.min(start + 1000, run.length());
                if (end < run.length()) {
                    int boundary = run.lastIndexOf(' ', end);
                    if (boundary > start) end = boundary;
                }
                String sample = run.substring(start, end).trim();
                start = end;
                if (sample.isEmpty()) continue;
                String normalized = normalize(sample);
                String detected;
                if (VIETNAMESE_GUIDANCE.matcher(normalized).find()) {
                    detected = "vi";
                } else if (ENGLISH_PROSE.matcher(normalized).find() || VIETNAMESE_PROSE.matcher(normalized).find()
                        || isUncapitalizedPhrase(sample)) {
                    detected = LocalLanguageDetector.detect(sample);
                } else {
                    detected = null;
                }
                if (detected == null) uncertain = true;
                else if (!language.code().equals(detected)) return Result.MISMATCH;
                else matched = true;
            }
        }
        var han = HAN_PROSE.matcher(prose);
        while (han.find()) {
            String sample = han.group();
            String detected = SHORT_CHINESE.contains(sample) ? "zh-CN"
                    : sample.length() >= 2 ? LocalLanguageDetector.detect(sample) : null;
            if (detected == null) uncertain = true;
            else if (!language.code().equals(detected)) return Result.MISMATCH;
            else matched = true;
        }
        return matched && !uncertain ? Result.MATCH : Result.UNCERTAIN;
    }

    private static String normalize(String text) {
        return MARKS.matcher(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
                .replaceAll("").replace('đ', 'd');
    }

    private static boolean isUncapitalizedPhrase(String sample) {
        String[] words = sample.split("[\\s'’\\-]+");
        if (words.length < 2) return false;
        // Unknown title-cased names remain uncertain until the caller supplies evidence.
        // Ordinary lower-case prose still reaches the offline detector without a phrase list.
        return java.util.Arrays.stream(words).anyMatch(word -> !word.isEmpty()
                && Character.isLowerCase(word.codePointAt(0)));
    }
}
