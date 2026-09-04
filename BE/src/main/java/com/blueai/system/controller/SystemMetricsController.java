package com.blueai.system.controller;

import com.blueai.system.dto.SystemMetricsResponse;
import com.blueai.system.service.SystemMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/metrics")
public class SystemMetricsController {

    private final SystemMetricsService metricsService;

    public SystemMetricsController(SystemMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping
    SystemMetricsResponse getMetrics() {
        return metricsService.getMetrics();
    }
}
