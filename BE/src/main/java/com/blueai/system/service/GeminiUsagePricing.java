package com.blueai.system.service;

import com.blueai.system.dto.GeminiUsageRangeReport.Cost;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public final class GeminiUsagePricing {
    public static final String SOURCE = "https://ai.google.dev/gemini-api/docs/pricing";
    public static final LocalDate AS_OF = LocalDate.of(2026, 11, 3);
    // Exact model IDs only: never silently charge an unknown model at another model's rate.
    private static final Map<String, Rate> RATES = Map.of(
            "gemini-3.5-flash-lite", new Rate("0.30", "2.50"),
            "gemini-3.1-flash-lite", new Rate("0.25", "1.50"),
            "gemini-3.1-flash-lite-preview", new Rate("0.25", "1.50"),
            "gemini-3-flash-preview", new Rate("0.50", "3.00"),
            "gemini-2.5-flash", new Rate("0.30", "2.50"),
            "gemini-2.5-flash-lite", new Rate("0.10", "0.40"),
            "gemini-2.5-pro", new Rate("1.25", "10.00")
    );
    private GeminiUsagePricing() {}

    public static Cost estimate(String model, boolean longPrompt, long requests, long input, long output) {
        String id = model != null && model.startsWith("models/") ? model.substring(7) : model;
        Rate rate = id == null ? null : RATES.get(id);
        if (rate == null) return new Cost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, requests);
        if ("gemini-2.5-pro".equals(id) && longPrompt) rate = new Rate("2.50", "15.00");
        BigDecimal inputUsd = BigDecimal.valueOf(input).multiply(rate.input).movePointLeft(6);
        BigDecimal outputUsd = BigDecimal.valueOf(output).multiply(rate.output).movePointLeft(6);
        return new Cost(inputUsd, outputUsd, inputUsd.add(outputUsd), 0);
    }

    private record Rate(BigDecimal input, BigDecimal output) {
        Rate(String input, String output) { this(new BigDecimal(input), new BigDecimal(output)); }
    }
}
