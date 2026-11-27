package com.blueai.rag.context;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A deterministic relevance scorer for the first release. It deliberately
 * avoids silently shipping an ungoverned vector index over private history.
 */
@Component
public class ContextRelevanceRanker {

    private static final Pattern NAMED_SUBJECT = Pattern.compile(
            "(?:^| )(?:khach(?: hang)?|customer|client|cong ty) ([a-z][a-z0-9]{2,})(?= |$)"
    );
    private static final Set<String> GENERIC_SUBJECTS = Set.of(
            "hang", "can", "cu", "moi", "truoc", "nay", "kia", "has", "have", "needs", "need", "with", "the", "sales"
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "anh", "ban", "cai", "cua", "cho", "co", "da", "day", "de", "do", "duoc",
            "em", "hay", "khong", "la", "lam", "minh", "mot", "nao", "nhung", "noi", "roi",
            "sao", "se", "the", "thi", "thong", "tin", "toan", "toi", "trong", "va", "ve", "voi", "vua", "y"
    );
    private static final Set<String> FOLLOW_UP_MARKERS = Set.of(
            "cai do", "cai nay", "con cai do", "giai thich lai", "noi ro hon", "chi tiet hon",
            "vi du", "tiep tuc", "phuong an do", "goi do", "buoc do", "nhu vay", "vay", "the con", "con do", "y nay",
            "tell me more", "explain again", "more details", "that plan", "this plan", "that step", "continue"
    );
    private static final Set<String> CONTEXT_RECALL_MARKERS = Set.of(
            "con nho", "ban nho", "nho lai", "nhac lai", "da noi", "da cung cap", "toi da cung cap",
            "minh da cung cap", "truoc do", "hoi thoai truoc", "context", "ngu canh", "ghi nho",
            "tom tat co hoi", "noi dung toi da cung cap", "noi dung minh da cung cap",
            "what did i tell you", "what did i say", "i told you", "remember", "previous conversation", "summarize"
    );
    // A mixed request must still retrieve documents. Context never establishes
    // company policy or troubleshooting procedures, even if mentioned earlier.
    private static final Set<String> DOCUMENT_INTENT_MARKERS = Set.of(
            "knowledge base", "kho tri thuc", "tai lieu", "dan nguon", "chinh sach",
            "quy trinh", "huong dan", "cach", "xu ly", "hoan tien", "dieu kien",
            "apn", "data roaming", "bao hanh", "bang gia", "chiet khau", "so sanh",
            // Advice and a new follow-up question need evidence even when the
            // same message also asks us to recall facts. Product/device names
            // alone are not markers: recalling a recorded eSIM or lock status
            // must remain possible without unrelated document retrieval.
            "tu van", "de xuat", "khuyen nghi", "nen chon", "nen dung", "thi sao",
            "co dung duoc", "co ho tro", "recommend", "advise", "advice",
            "how to", "troubleshoot", "compare", "refund", "policy", "price", "installation", "activation", "coverage"
    );

    public double score(String query, String candidate) {
        Set<String> queryTerms = meaningfulTerms(query);
        if (queryTerms.isEmpty() || candidate == null || candidate.isBlank()) return 0;
        Set<String> candidateTerms = meaningfulTerms(candidate);
        long overlap = queryTerms.stream().filter(candidateTerms::contains).count();
        if (overlap == 0) return 0;

        String normalizedQuery = normalize(query);
        String normalizedCandidate = normalize(candidate);
        double coverage = (double) overlap / queryTerms.size();
        double phraseBonus = normalizedQuery.length() > 8 && normalizedCandidate.contains(normalizedQuery) ? 0.25 : 0;
        // A verbose or multilingual question must not dilute an explicit
        // customer name below the relevance threshold. Match whole tokens;
        // never grant the bonus for generic "customer needs/khách cần" text.
        Matcher subjects = NAMED_SUBJECT.matcher(normalizedQuery);
        double subjectBonus = 0;
        while (subjects.find()) {
            String subject = subjects.group(1);
            if (!STOP_WORDS.contains(subject) && !GENERIC_SUBJECTS.contains(subject)
                    && candidateTerms.contains(subject)) {
                subjectBonus = 0.35;
                break;
            }
        }
        return Math.min(1, coverage + phraseBonus + subjectBonus);
    }

    public boolean isFollowUpQuestion(String question) {
        String normalized = normalize(question);
        return normalized.length() <= 180 && (FOLLOW_UP_MARKERS.stream().anyMatch(normalized::contains)
                || CONTEXT_RECALL_MARKERS.stream().anyMatch(normalized::contains));
    }

    public boolean isContextRecallQuestion(String question) {
        String normalized = normalize(question);
        if (DOCUMENT_INTENT_MARKERS.stream().anyMatch(marker -> containsPhrase(normalized, marker))) {
            return false;
        }
        boolean mentionsContext = CONTEXT_RECALL_MARKERS.stream()
                .anyMatch(marker -> containsPhrase(normalized, marker));
        boolean requestsRecordedFacts = Set.of(
                "nho", "nhac lai", "tom tat", "da cung cap", "da noi", "ma co hoi",
                "so luong", "khach hang", "han bao gia", "dinh dang", "nhom sales",
                "what did i tell you", "what did i say", "remember", "summarize", "i told you"
        ).stream().anyMatch(marker -> containsPhrase(normalized, marker));
        return mentionsContext && requestsRecordedFacts;
    }

    private boolean containsPhrase(String text, String phrase) {
        return (" " + text + " ").contains(" " + phrase + " ");
    }

    public boolean isPreferenceKey(String key) {
        String normalized = normalize(key).replace(' ', '-');
        return normalized.contains("language") || normalized.contains("ngon-ngu")
                || normalized.contains("tone") || normalized.contains("format")
                || normalized.contains("style") || normalized.contains("preference");
    }

    private Set<String> meaningfulTerms(String value) {
        return Arrays.stream(normalize(value).split(" "))
                .filter(term -> term.length() >= 2 && !STOP_WORDS.contains(term))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
