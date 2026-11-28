package com.blueai.rag.chat.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectedFactsTest {
    @Test
    void masksOnlyLiteralAuthorizedTermsAndKeepsOriginalOffsets() {
        String text = "请选择Bluecom Japan Unlimited Premium，APN为vmobile.jp。";
        String masked = ProtectedFacts.maskAuthorizedTerms(text, List.of("Bluecom Japan Unlimited Premium", "vmobile.jp"));
        assertThat(masked).hasSameSizeAs(text).startsWith("请选择").endsWith("。");
        assertThat(masked).doesNotContain("Bluecom", "vmobile.jp").contains("，APN为");
        assertThat(text).contains("Bluecom Japan Unlimited Premium", "vmobile.jp");
    }

    @Test
    void namesAreCaseSensitiveLiteralValuesRatherThanRegexOrSubstrings() {
        String text = "database Data DATA data PLAN-A PLAN-AB a+b aab";
        assertThat(ProtectedFacts.maskAuthorizedTerms(text, List.of("data", "PLAN-A", "a+b")))
                .isEqualTo("database Data DATA      " + "       PLAN-AB     aab");
    }

    @Test
    void quotesAndBackticksDoNotAuthorizeTheirContents() {
        String text = "请看 `Please activate data roaming`，以及\"Please contact support\"。";
        assertThat(ProtectedFacts.maskAuthorizedTerms(text, List.of())).isEqualTo(text);
        assertThat(ProtectedFacts.maskAuthorizedTerms(text, List.of("data")))
                .contains("`Please activate", "roaming`", "Please contact support");
    }

    @Test
    void preservesUnmatchedUnicodeNamesAndSupportsRepeatedAndOverlappingAuthorizedTerms() {
        String text = "Nguyễn Văn An，北京，PLAN-A PLAN-A，套餐Bluecom Premium。";
        String masked = ProtectedFacts.maskAuthorizedTerms(text, List.of("PLAN-A", "Bluecom", "Bluecom Premium", "北京"));
        assertThat(masked).hasSameSizeAs(text).contains("Nguyễn Văn An", "套餐");
        assertThat(masked).doesNotContain("PLAN-A", "Bluecom", "Premium", "北京");
    }

    @Test
    void absentOrEmptyTermsCannotHideText() {
        assertThat(ProtectedFacts.maskAuthorizedTerms(null, List.of("anything"))).isEmpty();
        assertThat(ProtectedFacts.maskAuthorizedTerms("unchanged", null)).isEqualTo("unchanged");
        assertThat(ProtectedFacts.maskAuthorizedTerms("unchanged", Arrays.asList(null, "", " "))).isEqualTo("unchanged");
    }
}
