package com.blueai.quota;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Independent short-lived accounting transactions cannot starve behind long chat transactions. */
@Component
public class AiUsageDataAccess implements DisposableBean {
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger();
    private final HikariDataSource source;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final TransactionTemplate transaction;

    public AiUsageDataAccess(DataSource applicationSource) throws SQLException {
        HikariDataSource application = applicationSource.unwrap(HikariDataSource.class);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(application.getJdbcUrl());
        config.setUsername(application.getUsername());
        config.setPassword(application.getPassword());
        if (application.getDriverClassName() != null) config.setDriverClassName(application.getDriverClassName());
        Properties options = new Properties();
        options.putAll(application.getDataSourceProperties());
        config.setDataSourceProperties(options);
        if (application.getConnectionInitSql() != null) config.setConnectionInitSql(application.getConnectionInitSql());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(60_000);
        config.setInitializationFailTimeout(-1);
        config.setPoolName("BlueAiUsage-" + POOL_SEQUENCE.incrementAndGet());
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    JdbcTemplate jdbc() { return jdbc; }
    PlatformTransactionManager manager() { return manager; }
    public void write(Consumer<JdbcTemplate> action) {
        transaction.executeWithoutResult(ignored -> action.accept(jdbc));
    }
    @Override public void destroy() { source.close(); }
}
