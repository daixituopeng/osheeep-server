package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Guarded MySQL 8 evidence for fresh, production-V4 and current-V9 paths through V10. */
public class DinnerSubscriptionMigrationMySqlIT {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Test
    void migratesFreshProductionV4AndCurrentV9CatalogsThroughV10() {
        String jdbcUrl = effectiveJdbcUrl();
        DataSource baseDataSource = new DriverManagerDataSource(
                jdbcUrl,
                System.getenv("OSHEEEP_DB_USERNAME"),
                System.getenv("OSHEEEP_DB_PASSWORD"));

        try (DinnerEphemeralCatalogHarness harness =
                DinnerEphemeralCatalogHarness.fromEnvironment(baseDataSource, jdbcUrl)) {
            harness.createCatalog(harness.freshCatalog());
            harness.createCatalog(harness.v4Catalog());
            harness.createCatalog(harness.v6Catalog());

            migrateFresh(harness);
            migrateProductionV4(harness);
            migrateCurrentV9(harness);
        }
    }

    private void migrateFresh(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.freshCatalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        migrate(harness, dataSource, catalog, null);
        assertV10(new JdbcTemplate(dataSource), catalog);
    }

    private void migrateProductionV4(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v4Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("4"));

        jdbcTemplate.update(
                "INSERT INTO users (id, username, display_name, status) "
                        + "VALUES (1040001, 'v10-production-user', "
                        + "'V10 migration user', 'ACTIVE')");
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) "
                        + "VALUES (1040101, 'V10 production household', 1040001)");
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members (household_id, user_id) "
                        + "VALUES (1040101, 1040001)");

        migrate(harness, dataSource, catalog, null);

        assertV10(jdbcTemplate, catalog);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_households WHERE id = 1040101",
                        Integer.class))
                .isEqualTo(1);
    }

    private void migrateCurrentV9(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v6Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("9"));
        assertLatestSuccessfulVersion(jdbcTemplate, "9");

        migrate(harness, dataSource, catalog, null);
        assertV10(jdbcTemplate, catalog);
    }

    private void assertV10(JdbcTemplate jdbcTemplate, String catalog) {
        assertLatestSuccessfulVersion(jdbcTemplate, "10");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_subscription_deliveries'",
                        Integer.class,
                        catalog))
                .isEqualTo(19);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_subscription_deliveries' "
                                + "AND CONSTRAINT_TYPE = 'CHECK' AND ENFORCED = 'YES'",
                        Integer.class,
                        catalog))
                .isGreaterThanOrEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_subscription_deliveries'",
                        Integer.class,
                        catalog))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_subscription_deliveries' "
                                + "AND COLUMN_NAME IN ('template_id', 'openid')",
                        Integer.class,
                        catalog))
                .isZero();

        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 23, 10, 0);
        insertWaiting(
                jdbcTemplate,
                1050001L,
                1050101L,
                "PARTNER_JOINED",
                "10000000-0000-0000-0000-000000000001",
                createdAt);
        insertReady(
                jdbcTemplate,
                1050002L,
                1050101L,
                "MENU_CHANGED",
                "10000000-0000-0000-0000-000000000002",
                "a".repeat(64),
                createdAt);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_subscription_deliveries",
                        Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> insertWaiting(
                        jdbcTemplate,
                        1050001L,
                        1050101L,
                        "PARTNER_JOINED",
                        "10000000-0000-0000-0000-000000000001",
                        createdAt))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertReady(
                        jdbcTemplate,
                        1050003L,
                        1050101L,
                        "MENU_COMPLETED",
                        "10000000-0000-0000-0000-000000000003",
                        "a".repeat(64),
                        createdAt))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO dinner_subscription_deliveries "
                                + "(recipient_id, household_id, scenario, request_key, "
                                + "outcome, status, created_at, updated_at, expires_at) "
                                + "VALUES (?, ?, 'MENU_COMPLETED', ?, "
                                + "'REJECT', 'WAITING_EVENT', ?, ?, ?)",
                        1050004L,
                        1050101L,
                        "10000000-0000-0000-0000-000000000004",
                        createdAt,
                        createdAt,
                        createdAt.plusDays(90)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO dinner_subscription_deliveries "
                                + "(recipient_id, household_id, scenario, request_key, "
                                + "outcome, status, notification_type, reference_type, "
                                + "reference_id, event_dedupe_key, created_at, updated_at, "
                                + "expires_at) VALUES (?, ?, 'MENU_COMPLETED', ?, "
                                + "'ACCEPT', 'WAITING_EVENT', 'MENU_COMPLETED', 'MENU', "
                                + "?, ?, ?, ?, ?)",
                        1050005L,
                        1050101L,
                        "10000000-0000-0000-0000-000000000005",
                        1050201L,
                        "b".repeat(64),
                        createdAt,
                        createdAt,
                        createdAt.plusDays(90)))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertWaiting(
            JdbcTemplate jdbcTemplate,
            long recipientId,
            long householdId,
            String scenario,
            String requestKey,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                "INSERT INTO dinner_subscription_deliveries "
                        + "(recipient_id, household_id, scenario, request_key, "
                        + "outcome, status, created_at, updated_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, 'ACCEPT', 'WAITING_EVENT', ?, ?, ?)",
                recipientId,
                householdId,
                scenario,
                requestKey,
                createdAt,
                createdAt,
                createdAt.plusDays(90));
    }

    private void insertReady(
            JdbcTemplate jdbcTemplate,
            long recipientId,
            long householdId,
            String scenario,
            String requestKey,
            String eventDedupeKey,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                "INSERT INTO dinner_subscription_deliveries "
                        + "(recipient_id, household_id, scenario, request_key, "
                        + "outcome, status, notification_type, reference_type, "
                        + "reference_id, reference_version, event_dedupe_key, "
                        + "attempt_count, next_attempt_at, created_at, updated_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, 'ACCEPT', 'READY', "
                        + "'PARTNER_SELECTION_UPDATED', 'MENU', ?, 1, ?, 0, ?, ?, ?, ?)",
                recipientId,
                householdId,
                scenario,
                requestKey,
                householdId + 1000,
                eventDedupeKey,
                createdAt,
                createdAt,
                createdAt,
                createdAt.plusDays(90));
    }

    private void migrate(
            DinnerEphemeralCatalogHarness harness,
            DataSource dataSource,
            String catalog,
            MigrationVersion target
    ) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .defaultSchema(catalog)
                .schemas(catalog)
                .createSchemas(false);
        if (target != null) {
            configuration.target(target);
        }
        Flyway flyway = configuration.load();
        Configuration effective = flyway.getConfiguration();
        assertThat(effective.getDefaultSchema()).isEqualTo(catalog);
        assertThat(effective.getSchemas()).containsExactly(catalog);
        assertThat(effective.isCreateSchemas()).isFalse();
        harness.requireActiveCatalog(effective.getDataSource(), catalog);
        flyway.migrate();
    }

    private void assertLatestSuccessfulVersion(
            JdbcTemplate jdbcTemplate,
            String expected
    ) {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version FROM flyway_schema_history "
                                + "WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1",
                        String.class))
                .isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0",
                        Integer.class))
                .isZero();
    }

    private String effectiveJdbcUrl() {
        String host = System.getenv("OSHEEEP_DB_HOST");
        if (host != null && host.contains(":")
                && !(host.startsWith("[") && host.endsWith("]"))) {
            host = "[" + host + "]";
        }
        return "jdbc:mysql://"
                + host
                + ":"
                + System.getenv("OSHEEEP_DB_PORT")
                + "/"
                + System.getenv("OSHEEEP_DB_NAME")
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&serverTimezone=Asia/Shanghai";
    }
}
