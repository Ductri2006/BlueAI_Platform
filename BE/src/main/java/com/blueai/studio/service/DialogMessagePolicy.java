package com.blueai.studio.service;

import java.util.regex.Pattern;

/** Bounded lexical routing hints, not a claim of semantic intent recognition. */
final class DialogMessagePolicy {
    private static final Pattern GREETING_ONLY = Pattern.compile(
            "(?:(?:xin )?chao(?: ban|anh|chi|em|moi nguoi)?|hello|hi|hey|good morning|good afternoon|good evening)"
                    + "(?: a|nhe|nha)?|你好|您好|早上好|晚上好");
    private static final Pattern QUESTION_CUE = Pattern.compile(
            "(?:^| )(?:gi|nao|tai sao|vi sao|bao nhieu|ra sao|the nao|khi nao)(?: |$)"
                    + "|(?:^| )co .+ khong(?: |$)|(?:^| )duoc khong(?: |$)"
                    + "|(?:^| )(?:how|what|why|where|when|which|can|could|does|is|are|should)(?: |$)");
    private static final Pattern REQUEST_CUE = Pattern.compile(
            "(?:^| )(?:toi can|toi muon|cho toi|giup toi|minh can|minh muon|i need|i want|help me|please help|i can t|i cannot|not working)(?: |$)");

    private DialogMessagePolicy() { }

    static boolean isGreetingOnly(String normalized) {
        return GREETING_ONLY.matcher(normalized).matches();
    }

    static boolean isIndependentRequest(String message, String normalized, boolean hasRecognizedEntity) {
        int words = normalized.isBlank() ? 0 : normalized.split(" ").length;
        long han = normalized.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN).count();
        boolean chineseRequest = han >= 4 && !isGreetingOnly(normalized)
                && (message.contains("？") || message.contains("?")
                || normalized.matches(".*(?:如何|怎么|为什么|什么|多少|请问|无法|不能|没有网络|安装|激活|退款|我想|我需要|请帮).*"));
        // Short slot replies and ambiguous acknowledgements stay in Dialog.
        // An unmatched substantive question must not be trapped by that state.
        return chineseRequest || (words >= 4 && (message.contains("?") || QUESTION_CUE.matcher(normalized).find()))
                || (words >= 4 && !hasRecognizedEntity && REQUEST_CUE.matcher(normalized).find());
    }
}
