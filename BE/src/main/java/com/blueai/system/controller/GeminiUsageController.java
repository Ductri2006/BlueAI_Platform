package com.blueai.system.controller;

import com.blueai.system.service.GeminiUsageRangeService;
import com.blueai.system.service.GeminiUsageService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/system/gemini-usage")
public class GeminiUsageController {

    private final GeminiUsageService usageService;
    private final GeminiUsageRangeService rangeService;

    public GeminiUsageController(GeminiUsageService usageService, GeminiUsageRangeService rangeService) {
        this.usageService = usageService;
        this.rangeService = rangeService;
    }

    @GetMapping
    public Object getUsage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        if (startDate == null && endDate == null) return usageService.report();
        return rangeService.report(startDate, endDate, page, pageSize);
    }
}
