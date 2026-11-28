package com.blueai.rag.chat.service;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;

/** Offline detector. Candidate languages include Japanese/Korean to avoid forcing them into Chinese. */
final class LocalLanguageDetector {
    private LocalLanguageDetector() { }

    private static final class Holder {
        private static final LanguageDetector DETECTOR = LanguageDetectorBuilder.fromLanguages(
                Language.VIETNAMESE, Language.ENGLISH, Language.CHINESE, Language.JAPANESE, Language.KOREAN)
                .withMinimumRelativeDistance(0.15).build();
    }

    static String detect(String text) {
        // Bound CPU/memory for pasted content; don't persist/log message text here.
        String sample = text.length() > 1000 ? text.substring(0, 1000) : text;
        Language language = Holder.DETECTOR.detectLanguageOf(sample);
        return switch (language) {
            case VIETNAMESE -> "vi";
            case ENGLISH -> "en";
            case CHINESE -> "zh-CN";
            default -> null;
        };
    }
}
