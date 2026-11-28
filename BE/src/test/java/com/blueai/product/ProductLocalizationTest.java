package com.blueai.product;

import com.blueai.rag.chat.service.ReplyLanguagePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class ProductLocalizationTest {
    private ProductFacts plan(String name, int days, String mb, ProductFacts.DataAllowance.Period period) {
        return new ProductFacts("BLU-ESIM-JP-7D",name,Set.of("JP"),days,
                new ProductFacts.DataAllowance(new BigDecimal(mb),"MB",period),new BigDecimal("101000"),"VND",
                new ProductFacts.Provenance("https://production.example.test/api","PRODUCTION","snapshot",Instant.now()));
    }
    @ParameterizedTest @CsvSource({"vi,Nhật Bản · 3 GB tổng dung lượng · 7 ngày", "en,Japan · 3 GB total data · 7 days", "zh-CN,日本 · 3 GB（总流量） · 7 天"})
    void buildsLocalizedNamesFromFactsWithoutEchoingVendorNames(String language,String expected) {
        var fact=plan("Japan (Total 3GB) - 07 Ngày",7,"3072",ProductFacts.DataAllowance.Period.TOTAL);
        assertThat(ProductLabels.name(fact,language)).isEqualTo(expected);
        String text=new ProductAdvisor.Grounding("",List.of(),ProductAdvisor.Status.MATCHES,List.of(fact),Set.of())
                .deterministicReply(new ReplyLanguagePolicy.State(language,true)).orElseThrow();
        assertThat(text).contains(expected,fact.sku(),"VND").doesNotContain(fact.name(),"[S1]");
        if (!language.equals("vi")) assertThat(text).doesNotContain("Ngày","ngày","giá bán");
    }
    @Test void dailyTotalUnlimitedAndUnknownRemainDistinct() {
        var daily=plan("Japan 3GB/day 7 days",7,"3072",ProductFacts.DataAllowance.Period.DAILY);
        assertThat(ProductLabels.name(daily,"en")).contains("3 GB/day").doesNotContain("total");
        assertThat(ProductLabels.data(new ProductFacts.DataAllowance(null,"MB",ProductFacts.DataAllowance.Period.TOTAL,true),"zh-CN")).contains("不限量");
        assertThat(ProductLabels.data(null,"en")).isEqualTo("data allowance not verified");
        assertThat(ProductLabels.data(new ProductFacts.DataAllowance(new BigDecimal("1500.5"),"MB",ProductFacts.DataAllowance.Period.UNKNOWN),"en"))
                .isEqualTo("1500.5 MB (total/daily basis not verified)");
    }
    @ParameterizedTest @CsvSource(value={"vi|101.000 VND", "en|101,000 VND", "zh-CN|101,000 VND"}, delimiter='|')
    void formatsCurrencyWithoutConvertingAmounts(String language,String expected) {
        assertThat(ProductLabels.price(new BigDecimal("101000"),"VND",language)).isEqualTo(expected);
    }
    @Test void multiCountryNamesDoNotClaimWholeContinentCoverage() {
        var fact=new ProductFacts("REGION-1","Asia (Total 3GB) - 07 Ngày",Set.of("JP","KR"),7,null,null,null,null);
        assertThat(ProductLabels.name(fact,"en")).startsWith("Multi-country eSIM").doesNotContain("Asia");
    }
    @Test void rejectsExplicitDurationVolumePeriodConflictsAndTestRecords() {
        assertThat(ProductQuality.issues(plan("Japan (Total 3GB) - 30 Ngày",3,"3072",ProductFacts.DataAllowance.Period.TOTAL))).contains("VALIDITY_CONFLICT");
        assertThat(ProductQuality.issues(plan("Japan (Total 5GB) - 07 Ngày",7,"3072",ProductFacts.DataAllowance.Period.TOTAL))).contains("DATA_AMOUNT_CONFLICT");
        assertThat(ProductQuality.issues(plan("Japan (Total 3GB) - 07 Ngày",7,"3072",ProductFacts.DataAllowance.Period.DAILY))).contains("DATA_PERIOD_CONFLICT");
        assertThat(ProductQuality.issues(plan("Japan 3GB/ngày - 07 Ngày",7,"3072",ProductFacts.DataAllowance.Period.TOTAL))).contains("DATA_PERIOD_CONFLICT");
        for(String name:List.of("DEMO-JP7","[TEST] Japan","DỮ LIỆU THỬ NGHIỆM","演示套餐"))
            assertThat(ProductQuality.issues(plan(name,7,"3072",ProductFacts.DataAllowance.Period.TOTAL))).contains("TEST_RECORD");
        assertThat(ProductQuality.issues(plan("Japan (Total 3GB) - 07 Ngày",7,"3072",ProductFacts.DataAllowance.Period.TOTAL))).isEmpty();
        assertThat(ProductQuality.issues(plan("Latest Japan offer",7,"3072",ProductFacts.DataAllowance.Period.TOTAL))).isEmpty();
    }
}
