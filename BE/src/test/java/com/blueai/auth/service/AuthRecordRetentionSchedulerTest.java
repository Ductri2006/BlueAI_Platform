package com.blueai.auth.service;

import com.blueai.auth.config.AuthRetentionProperties;
import com.blueai.auth.config.AuthRecordRetentionSchedulingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthRecordRetentionSchedulerTest {

    @Test
    void schedulerIsDisabledUnlessExplicitlyEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuthRecordRetentionSchedulingConfiguration.class)
                .withPropertyValues("app.auth.retention.scheduled-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AuthRecordRetentionScheduler.class));
    }

    @Test
    void enabledSchedulerUsesConfiguredPolicyAndDoesNotIdentifyAnAdministrator() {
        AuthRecordRetentionService service = mock(AuthRecordRetentionService.class);
        AuthRecordRetentionScheduler scheduler = new AuthRecordRetentionScheduler(service, 45);

        scheduler.run();

        verify(service).purge(45, null);
    }

    @Test
    void enabledConfigurationCreatesSchedulerWithSafeDefaultsAndConfiguredInterval() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuthRecordRetentionSchedulingConfiguration.class)
                .withBean(AuthRecordRetentionService.class, () -> mock(AuthRecordRetentionService.class))
                .withPropertyValues(
                        "app.auth.retention.scheduled-enabled=true",
                        "app.auth.retention.retention-days=60",
                        "app.auth.retention.interval=6h",
                        "app.auth.retention.initial-delay=12h"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuthRecordRetentionScheduler.class);
                    AuthRetentionProperties properties = context.getBean(AuthRetentionProperties.class);
                    assertThat(properties.getRetentionDays()).isEqualTo(60);
                    assertThat(properties.getInterval()).isEqualTo(Duration.ofHours(6));
                    assertThat(properties.getInitialDelay()).isEqualTo(Duration.ofHours(12));
                });
    }
}
