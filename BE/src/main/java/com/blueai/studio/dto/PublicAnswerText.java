package com.blueai.studio.dto;

import java.util.regex.Pattern;
import java.util.ArrayList;
import org.springframework.web.util.HtmlUtils;

/** Chat presentation shared by web, preview, widget and messaging integrations. */
public final class PublicAnswerText {
    private static final Pattern SOURCES = Pattern.compile(
            "(?i)[ \\t]*(?:\\*\\*|__)?\\[[ \\t]*S\\d+(?:[ \\t]*,[ \\t]*(?:S)?\\d+)*[ \\t]*](?:\\*\\*|__)?");
    private PublicAnswerText() {}

    public static String toPlainText(String text) {
        if (text == null || text.isEmpty()) return text;
        String value = text.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < 4; i++) {
            String decoded = HtmlUtils.htmlUnescape(value).replace("&apos;", "'");
            if (decoded.equals(value)) break;
            value = decoded;
        }
        value = value.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", "")
                .replaceAll("(?i)<((?:https?://|mailto:)[^<>\\s]+)>", "$1")
                .replaceAll("(?i)<br\\s*/?>|</(?:p|div|li|h[1-6]|tr|blockquote)\\s*>", "\n")
                .replaceAll("(?i)<li(?:\\s[^<>]*)?>", "- ")
                .replaceAll("</?[A-Za-z][A-Za-z0-9]*(?:\\s[^<>]*|\\s*/?)>", "")
                .replaceAll("(?m)^\\h*(?:`{3,}|~{3,})[^\\n]*$", "")
                .replaceAll("(?m)^\\h{0,3}#{1,6}\\h+", "")
                .replaceAll("(?m)^\\h*>\\h?", "")
                .replaceAll("(?m)^\\h*(?:[-*_]\\h*){3,}$", "")
                .replaceAll("(?i)\\[\\h*S\\d+(?:\\h*,\\h*S?\\d+)*\\h*]\\([^\\n)]*\\)", "");
        value = withoutSources(value);
        value = value.replaceAll("!?\\[([^]\\n]+)]\\(([^)\\n]+)\\)", "$1 ($2)");
        value = plainTables(value);
        // Match paired formatting only: keep SKU underscores, prices, URLs and arithmetic intact.
        for (int i = 0; i < 3; i++) {
            value = value.replaceAll("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1", "$2")
                    .replaceAll("(?<![\\p{L}\\p{N}_])([*_])(?=\\S)(.+?)(?<=\\S)\\1(?![\\p{L}\\p{N}_])", "$2");
        }
        return value.replaceAll("`+([^`\\n]+)`+", "$1")
                .replaceAll("~~([^~\\n]+)~~", "$1")
                .replaceAll("(?m)^(\\h*)[*+]\\h+", "$1- ")
                .replace('\u00a0', ' ')
                .replaceAll("[ \\t]+([,.;:!?])", "$1")
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String plainTables(String text) {
        String[] lines = text.split("\n", -1);
        var output = new ArrayList<String>();
        for (int i = 0; i < lines.length; i++) {
            if (i + 1 < lines.length && lines[i].contains("|")
                    && lines[i + 1].matches("\\h*\\|?\\h*:?-{3,}:?\\h*(?:\\|\\h*:?-{3,}:?\\h*)+\\|?\\h*")) {
                String[] headers = tableCells(lines[i]);
                i += 2;
                for (; i < lines.length && lines[i].contains("|") && !lines[i].isBlank(); i++) {
                    String[] cells = tableCells(lines[i]);
                    var fields = new ArrayList<String>();
                    for (int c = 0; c < cells.length; c++)
                        fields.add((c < headers.length ? headers[c].strip() + ": " : "") + cells[c].strip());
                    output.add(String.join("; ", fields));
                }
                i--;
            } else output.add(lines[i]);
        }
        return String.join("\n", output);
    }

    private static String[] tableCells(String line) {
        return line.strip().replaceAll("^\\||\\|$", "").split("\\|", -1);
    }

    public static String withoutSources(String text) {
        if (text == null || !SOURCES.matcher(text).find()) return text;
        return SOURCES.matcher(text).replaceAll("")
                .replaceAll("[ \\t]+([,.;:!?])", "$1")
                .replaceAll("(?m)[ \\t]+$", "").strip();
    }
}
