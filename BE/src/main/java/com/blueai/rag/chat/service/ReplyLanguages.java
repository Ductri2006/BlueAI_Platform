package com.blueai.rag.chat.service;

import java.util.Map;

/** Registry shared by detection and output. Stored locale strings remain backward compatible. */
public final class ReplyLanguages {
    private static final Map<String, String> NAMES = Map.of(
            "vi", "Vietnamese", "en", "English", "zh-CN", "Simplified Chinese");
    private ReplyLanguages() { }

    public static boolean chineseEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("blueai.chat.chinese-enabled",
                System.getenv().getOrDefault("APP_CHAT_CHINESE_ENABLED", "true")));
    }

    public static String canonical(String code) {
        if ("zh-CN".equals(code) && !chineseEnabled()) return "vi";
        return NAMES.containsKey(code == null ? "" : code) ? code : "vi";
    }

    public static String name(String code) { return NAMES.get(canonical(code)); }
}
