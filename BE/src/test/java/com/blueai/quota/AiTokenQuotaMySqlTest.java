package com.blueai.quota;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Run the same admission, settlement and concurrent-tab contract on production's database engine. */
@EnabledIfEnvironmentVariable(named="MYSQL_TOKEN_QUOTA_TEST_URL", matches=".+")
class AiTokenQuotaMySqlTest extends AiTokenQuotaServiceTest {
    @Override DriverManagerDataSource source() {
        String url = System.getenv("MYSQL_TOKEN_QUOTA_TEST_URL");
        assertThat(url).matches("jdbc:mysql://[^/]+/blueai_token_quota_test(?:\\?.*)?");
        var source = new DriverManagerDataSource(url, System.getenv("MYSQL_TOKEN_QUOTA_TEST_USERNAME"), System.getenv("MYSQL_TOKEN_QUOTA_TEST_PASSWORD"));
        var fixture = new JdbcTemplate(source);
        // This named, isolated fixture contains no application or customer tables.
        fixture.execute("DROP TABLE IF EXISTS ai_token_operations");
        fixture.execute("DROP TABLE IF EXISTS ai_project_windows");
        fixture.execute("DROP TABLE IF EXISTS ai_quota_accounts");
        return source;
    }
}
