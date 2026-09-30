package com.blueai.migration;

import com.blueai.BlueAiAuthApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BlueAiAuthApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:blueai-auth-prod-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "app.widget.protection.redis-enabled=true",
        "app.widget.protection.client-key-salt=01234567890123456789012345678901",
        "app.widget.protection.trust-forwarded-headers=false",
        "app.jwt.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXphYmNkZWZnaGlqa2xtbm9wcQ==",
        "app.worker.api-key=01234567890123456789012345678901",
        "spring.datasource.password=non-development-test-password",
        "app.cors.allowed-origins=https://app.blueai.test",
        "app.cors.widget-allowed-origins=https://widget.blueai.test",
        "app.auth.public-registration-enabled=false",
        "app.rag.allow-shared-demo-knowledge=false",
        "app.bootstrap-admin.email=",
        "app.bootstrap-admin.password="
})
@ActiveProfiles({"test", "prod"})
class ProductionNoMailConfigurationIntegrationTest {

    @Autowired ApplicationContext context;

    @Test
    void productionProfileStartsWithoutSmtpOrPasswordResetConfiguration() {
        assertThat(context).isNotNull();
    }
}
