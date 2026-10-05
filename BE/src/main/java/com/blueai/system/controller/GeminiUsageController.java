package com.blueai.system.controller;

import com.blueai.system.dto.GeminiUsageReport;
import com.blueai.system.service.GeminiUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/gemini-usage")
public class GeminiUsageController {

    private final GeminiUsageService usageService;

    public GeminiUsageController(GeminiUsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping
    public GeminiUsageReport getUsage() {
        return usageService.report();
    }
}
