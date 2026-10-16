package com.blueai.studio.service;

import java.util.regex.Pattern;

/** Bounded lexical routing hints, not a claim of semantic intent recognition. */
final class DialogMessagePolicy {
    private static final Pattern GREETING_ONLY = Pattern.compile(
            "(?:(?:xin )?chao(?: ban|anh|chi|em|moi nguoi)?|hello|hi|hey|good morning|good afternoon|good evening)"
                    + "(?: a|nhe|nha)?");
    private static final Pattern QUESTION_CUE = Pattern.compile(
            "(?:^| )(?:gi|nao|tai sao|vi sao|bao nhieu|ra sao|the nao|khi nao)(?: |$)"
                    + "|(?:^| )co .+ khong(?: |$)|(?:^| )duoc khong(?: |$)");
    private static final Pattern REQUEST_CUE = Pattern.compile(
            "(?:^| )(?:toi can|toi muon|cho toi|giup toi|minh can|minh muon)(?: |$)");

    private DialogMessagePolicy() { }

    static boolean isGreetingOnly(String normalized) {
        return GREETING_ONLY.matcher(normalized).matches();
    }

    static boolean isIndependentRequest(String message, String normalized, boolean hasRecognizedEntity) {
        int words = normalized.isBlank() ? 0 : normalized.split(" ").length;
        // Short slot replies and ambiguous acknowledgements stay in Dialog.
        // An unmatched substantive question must not be trapped by that state.
        return (words >= 4 && (message.contains("?") || QUESTION_CUE.matcher(normalized).find()))
                || (words >= 6 && !hasRecognizedEntity && REQUEST_CUE.matcher(normalized).find());
    }
}
