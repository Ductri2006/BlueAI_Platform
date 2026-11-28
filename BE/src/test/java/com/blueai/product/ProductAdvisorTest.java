package com.blueai.product;

import com.blueai.rag.chat.service.LlmChatMessage;
import com.blueai.rag.chat.service.ReplyLanguagePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductAdvisorTest {
    private final ProductCatalogService catalog = mock(ProductCatalogService.class);
    private final ProductAdvisor advisor = new ProductAdvisor(catalog, new ObjectMapper().findAndRegisterModules());
    private ProductFacts plan(String sku, String name, String amount, String currency, Integer days) {
        return new ProductFacts(sku,name,Set.of("JP"),days,null,amount == null ? null : new BigDecimal(amount),currency,
                new ProductFacts.Provenance("https://api.sandbox.bluesim.com.vn/api/open/v1","SANDBOX","snapshot-1",Instant.parse("2026-11-28T00:00:00Z")));
    }
    @BeforeEach void available() {
        when(catalog.integrationConfigured()).thenReturn(true);
        when(catalog.adviceSnapshot(anySet(),nullable(Integer.class),nullable(ProductCatalogService.Budget.class),nullable(String.class)))
                .thenReturn(new ProductCatalogService.AdviceSnapshot(ProductCatalogService.Availability.READY,
                        List.of(plan("BLU-ESIM-JP-31D","Japan eSIM 31 Days","19.9500","USD",31))));
    }

    @ParameterizedTest
    @ValueSource(strings={"How do I install my eSIM in Japan?", "eSIM activation error", "Cách cài đặt eSIM Nhật Bản 7 ngày",
            "eSIM không hoạt động", "请帮助安装日本eSIM", "日本eSIM无法连接网络", "eSIM", "Hello"})
    void supportAndBareProductWordsNeverInterceptTheConversation(String question) {
        var result = advisor.grounding(question,List.of());
        assertThat(result.status()).isEqualTo(ProductAdvisor.Status.NONE);
        assertThat(result.deterministicReply(new ReplyLanguagePolicy.State("zh",false))).isEmpty();
        verifyNoInteractions(catalog);
    }

    @Test void exactChineseMonthQuestionAsksDestinationAndThirtyOrThirtyOneDays() {
        var result = advisor.grounding("贵公司提供哪些 eSIM 套餐？是否有适合长达一个月的旅行的 eSIM 套餐？",List.of());
        assertThat(result.status()).isEqualTo(ProductAdvisor.Status.NEEDS_DETAILS);
        assertThat(result.details()).contains(ProductAdvisor.Detail.COUNTRY,ProductAdvisor.Detail.CALENDAR_MONTH);
        assertThat(result.deterministicReply(new ReplyLanguagePolicy.State("zh-CN",false)).orElseThrow())
                .contains("国家","30天","31天").doesNotContain("BLU-ESIM", "没有套餐");
        assertThat(result.sources()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"Recommend eSIM Japan 31 days", "Tư vấn eSIM Nhật Bản 31 ngày", "推荐日本eSIM31天", "推荐日本eSIM三十一天"})
    void destinationsAndExactTravelDurationAreSharedAcrossLanguages(String question) {
        assertThat(advisor.grounding(question,List.of()).status()).isEqualTo(ProductAdvisor.Status.MATCHES);
        verify(catalog).adviceSnapshot(Set.of("JP"),31,null,null);
    }

    @Test void allDestinationsAreRequiredAndPreviousUserDestinationCanCarryARealProductFollowup() {
        advisor.grounding("Recommend eSIM Japan and Singapore 31 days",List.of());
        verify(catalog).adviceSnapshot(Set.of("JP","SG"),31,null,null);
        clearInvocations(catalog);
        advisor.grounding("31 days",List.of(new LlmChatMessage("user","Which eSIM plans for Japan?")));
        verify(catalog).adviceSnapshot(Set.of("JP"),31,null,null);
    }

    @Test void currencyIsExplicitAndDollarsOrUnknownBudgetUnitsNeverBecomeVnd() {
        advisor.grounding("Recommend eSIM Japan 31 days under 20 USD",List.of());
        verify(catalog).adviceSnapshot(Set.of("JP"),31,new ProductCatalogService.Budget(new BigDecimal("20"),"USD"),null);
        for (String budget : List.of("under $20","under 20 dollars","dưới 200k","budget 20")) {
            var result = advisor.grounding("Recommend eSIM Japan 31 days "+budget,List.of());
            assertThat(result.status()).isEqualTo(ProductAdvisor.Status.NEEDS_DETAILS);
            assertThat(result.details()).contains(ProductAdvisor.Detail.BUDGET_CURRENCY);
        }
    }

    @Test void exactSkuIsNotAProductNameSubstringSearchAndDoesNotRequireDestination() {
        var result = advisor.grounding("Price for SKU BLU-ESIM-JP-31D",List.of());
        assertThat(result.status()).isEqualTo(ProductAdvisor.Status.MATCHES);
        verify(catalog).adviceSnapshot(Set.of(),null,null,"BLU-ESIM-JP-31D");
    }

    @Test void sameImmutableSnapshotRendersThreeLanguagesAndRetainsExactFactsAfterRefresh() {
        List<ProductFacts> products = new ArrayList<>(List.of(plan("BLU-ESIM-JP-31D","Gói Nhật 日本 eSIM","19.9500","USD",31)));
        when(catalog.adviceSnapshot(anySet(),any(),any(),any())).thenReturn(
                new ProductCatalogService.AdviceSnapshot(ProductCatalogService.Availability.READY,products));
        var snapshot = advisor.grounding("Recommend eSIM Japan 31 days",List.of());
        products.clear();
        reset(catalog);
        for (String language : List.of("vi","en","zh-CN")) {
            String reply = snapshot.deterministicReply(new ReplyLanguagePolicy.State(language,true)).orElseThrow();
            assertThat(reply).contains("BLU-ESIM-JP-31D","Gói Nhật 日本 eSIM","19.95 USD","31","SANDBOX")
                    .doesNotContain("19.95 VND","1024 MB");
        }
        assertThat(snapshot.sources()).hasSize(1);
        assertThat(snapshot.factNames()).contains("BLU-ESIM-JP-31D","Gói Nhật 日本 eSIM");
        assertThatThrownBy(()->snapshot.facts().clear()).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(catalog);
    }

    @Test void canonicalChineseLanguageStateSelectsChineseTemplatesInsteadOfVietnamese() {
        var result = advisor.grounding("推荐日本eSIM31天",List.of());
        var language = new ReplyLanguagePolicy.State("zh-CN",true);
        assertThat(result.deterministicReply(language).orElseThrow()).contains("已同步目录","建议零售价")
                .doesNotContain("Các gói","giá bán");
    }

    @Test void unknownFactsAreNotRenderedAsZeroOrInventedUnitsAndResponsesAreCapped() {
        List<ProductFacts> products = new ArrayList<>();
        for (int i=0;i<8;i++) products.add(plan("SKU-"+i,"Plan "+i,null,null,null));
        when(catalog.adviceSnapshot(anySet(),any(),any(),any())).thenReturn(
                new ProductCatalogService.AdviceSnapshot(ProductCatalogService.Availability.READY,products));
        var result = advisor.grounding("List eSIM plans for Japan",List.of());
        assertThat(result.facts()).hasSize(6);
        String reply = result.deterministicReply(new ReplyLanguagePolicy.State("en",false)).orElseThrow();
        assertThat(reply).contains("amount and currency not verified","amount and unit not verified","validity: not verified")
                .doesNotContain("SKU-6","0 VND"," GB");
    }

    @Test void unconfiguredAndStaleSourcesGetSafeLocalizedStatusesAndPublicAdviceIsOptIn() {
        when(catalog.adviceSnapshot(anySet(),any(),any(),any())).thenReturn(
                new ProductCatalogService.AdviceSnapshot(ProductCatalogService.Availability.UNAVAILABLE,List.of()));
        var unavailable = advisor.grounding("List eSIM plans for Japan",List.of());
        assertThat(unavailable.status()).isEqualTo(ProductAdvisor.Status.UNAVAILABLE);
        assertThat(unavailable.deterministicReply(new ReplyLanguagePolicy.State("zh-CN",false)).orElseThrow()).contains("并不代表没有套餐");
        when(catalog.adviceSnapshot(anySet(),any(),any(),any())).thenReturn(
                new ProductCatalogService.AdviceSnapshot(ProductCatalogService.Availability.STALE,List.of()));
        var stale = advisor.grounding("List eSIM plans for Japan",List.of());
        assertThat(stale.status()).isEqualTo(ProductAdvisor.Status.STALE);
        assertThat(stale.deterministicReply(new ReplyLanguagePolicy.State("en",false)).orElseThrow()).contains("out of date");
        clearInvocations(catalog);
        assertThat(advisor.groundingForPublic("List eSIM plans for Japan",List.of()).status()).isEqualTo(ProductAdvisor.Status.NONE);
        verify(catalog,never()).adviceSnapshot(anySet(),any(),any(),any());
    }

    @Test void absentOptionalIntegrationKeepsTheExistingKnowledgeBaseRoute() {
        when(catalog.integrationConfigured()).thenReturn(false);
        assertThat(advisor.grounding("List eSIM plans for Japan",List.of()).status()).isEqualTo(ProductAdvisor.Status.NONE);
        verify(catalog,never()).adviceSnapshot(anySet(),any(),any(),any());
    }
}
