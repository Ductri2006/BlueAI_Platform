package com.blueai.product;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.*;

/** Localized presentation of verified facts. Supplier names are never translated by a chat model. */
final class ProductLabels {
    private ProductLabels() { }
    static Locale locale(String language) {
        return language.startsWith("zh") ? Locale.SIMPLIFIED_CHINESE
                : language.startsWith("en") ? Locale.US : Locale.forLanguageTag("vi-VN");
    }
    static String label(String language, String key) {
        return ResourceBundle.getBundle("com.blueai.product.labels", locale(language)).getString(key);
    }
    static String country(String code, String language) {
        return new Locale.Builder().setRegion(code).build().getDisplayCountry(locale(language));
    }
    static String name(ProductFacts fact, String language) {
        String destination = fact.countryCodes().size() == 1 ? country(fact.countryCodes().iterator().next(),language)
                : label(language,fact.countryCodes().isEmpty() ? "travelEsim" : "multiCountry");
        String validity = fact.validityDays() == null ? label(language,"validityUnknown")
                : fact.validityDays() + " " + label(language,"days");
        return destination + " · " + data(fact.data(),language) + " · " + validity;
    }
    static String data(ProductFacts.DataAllowance data, String language) {
        if (data == null) return label(language,"dataUnknown");
        String amount;
        if (data.unlimited()) amount = label(language,"unlimited");
        else {
            BigDecimal value = data.amount();
            String unit = data.unit();
            // Bluecom documents MB, with its 10240 MB = Total 10GB example.
            // Only exact integer GB equivalents are shortened; other amounts retain their source unit.
            if ("MB".equals(unit) && value.compareTo(BigDecimal.valueOf(1024)) >= 0
                    && value.remainder(BigDecimal.valueOf(1024)).signum() == 0) {
                value = value.divide(BigDecimal.valueOf(1024)); unit = "GB";
            }
            amount = value.stripTrailingZeros().toPlainString() + " " + unit;
        }
        return amount + label(language,switch (data.period()) {
            case TOTAL -> "total"; case DAILY -> "daily"; case UNKNOWN -> "periodUnknown";
        });
    }
    static String price(BigDecimal amount, String currency, String language) {
        NumberFormat format = NumberFormat.getNumberInstance(locale(language));
        format.setMaximumFractionDigits(Math.max(0,amount.scale()));
        format.setMinimumFractionDigits(0);
        return format.format(amount) + " " + currency;
    }
}
