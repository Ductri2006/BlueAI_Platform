package com.blueai.auth.config;

import com.blueai.auth.service.AuthRecordRetentionScheduler;
import com.blueai.auth.service.AuthRecordRetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;

/** Enables auth retention only after an explicit opt-in. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuthRetentionProperties.class)
@ConditionalOnProperty(
        prefix = "app.auth.retention",
        name = "scheduled-enabled",
        havingValue = "true"
)
public class
AuthRecordRetentionSchedulingConfiguration implements SchedulingConfigurer {

    private final AuthRetentionProperties properties;
    private final AuthRecordRetentionService retentionService;

    public AuthRecordRetentionSchedulingConfiguration(
            AuthRetentionProperties properties,
            AuthRecordRetentionService retentionService
    ) {
        this.properties = properties;
        this.retentionService = retentionService;
    }

    @Bean
    AuthRecordRetentionScheduler authRecordRetentionScheduler() {
        return new AuthRecordRetentionScheduler(retentionService, properties.getRetentionDays());
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        AuthRecordRetentionScheduler scheduler = authRecordRetentionScheduler();
        taskRegistrar.addFixedDelayTask(new IntervalTask(
                scheduler,
                properties.getInterval(),
                properties.getInitialDelay()
        ));
    }
}
