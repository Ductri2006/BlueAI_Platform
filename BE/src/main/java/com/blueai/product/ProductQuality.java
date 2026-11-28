package com.blueai.product;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Fail closed for explicit test records and contradictions; never guess corrected supplier facts. */
final class ProductQuality {
    private ProductQuality() { }
    private static final Pattern DEMO = Pattern.compile("(?i)(?:^|[^a-z0-9])(?:demo|test|sample|draft|qa)(?:$|[^a-z0-9])|thu nghiem|du lieu mau|测试|測試|演示");
    private static final Pattern DAYS = Pattern.compile("(?<![\\d.])(\\d{1,4})\\s*(?:ngay\\b|days?\\b|天|日)");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*(GB|MB)\\b");
    static Set<String> issues(ProductFacts fact) {
        Set<String> issues = new LinkedHashSet<>();
        String name = ProductCatalogService.normalize(fact.name() == null ? "" : fact.name());
        if (DEMO.matcher(ProductCatalogService.normalize(fact.sku())+" "+name).find()) issues.add("TEST_RECORD");
        var days = DAYS.matcher(name);
        while (days.find()) if (fact.validityDays() != null && Integer.parseInt(days.group(1)) != fact.validityDays())
            issues.add("VALIDITY_CONFLICT");
        var data = fact.data();
        if (data != null) {
            if (name.contains("total") && data.period() == ProductFacts.DataAllowance.Period.DAILY)
                issues.add("DATA_PERIOD_CONFLICT");
            if (Pattern.compile("/\\s*(?:ngay|day)|per day|moi ngay|每天|每日").matcher(name).find()
                    && data.period() == ProductFacts.DataAllowance.Period.TOTAL) issues.add("DATA_PERIOD_CONFLICT");
            var amount = AMOUNT.matcher(name);
            if (!data.unlimited() && "MB".equals(data.unit())) while (amount.find()) {
                BigDecimal value = new BigDecimal(amount.group(1));
                if (amount.group(2).equalsIgnoreCase("GB")) value = value.multiply(BigDecimal.valueOf(1024));
                if (value.compareTo(data.amount()) != 0) issues.add("DATA_AMOUNT_CONFLICT");
            }
        }
        return Collections.unmodifiableSet(issues);
    }
    static boolean eligible(ProductFacts fact) { return issues(fact).isEmpty(); }
}
