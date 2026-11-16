package com.blueai.studio.dto;

import java.util.regex.Pattern;

/** Removes BlueAI document markers only at the external response boundary. */
public final class PublicAnswerText {
    private static final Pattern SOURCES = Pattern.compile(
            "(?i)[ \\t]*(?:\\*\\*|__)?\\[[ \\t]*S\\d+(?:[ \\t]*,[ \\t]*(?:S)?\\d+)*[ \\t]*](?:\\*\\*|__)?");
    private PublicAnswerText() {}

    public static String withoutSources(String text) {
        if (text == null || !SOURCES.matcher(text).find()) return text;
        return SOURCES.matcher(text).replaceAll("")
                .replaceAll("[ \\t]+([,.;:!?])", "$1")
                .replaceAll("(?m)[ \\t]+$", "").strip();
    }
}
