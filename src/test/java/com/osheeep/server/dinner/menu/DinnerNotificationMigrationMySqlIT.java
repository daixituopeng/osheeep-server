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

/** Guarded MySQL 8 evidence for fresh, production-V4 and current-V8 paths through V9. */
public class DinnerNotificationMigrationMySqlIT {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Test
    void migratesFreshProductionV4AndCurrentV8CatalogsThroughV9() {
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
            migrateCurrentV8(harness);
        }
    }

    private void migrateFresh(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.freshCatalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        migrate(harness, dataSource, catalog, null);
        assertV9(new JdbcTemplate(dataSource), catalog);
    }

    private void migrateProductionV4(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v4Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("4"));

        jdbcTemplate.update(
                "INSERT INTO users (id, username, display_name, status) "
                        + "VALUES (940001, 'v9-production-user', 'V9 migration user', 'ACTIVE')");
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) "
                        + "VALUES (940101, 'V9 production household', 940001)");
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members (household_id, user_id) "
                        + "VALUES (940101, 940001)");

        migrate(harness, dataSource, catalog, null);

        assertV9(jdbcTemplate, catalog);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_households WHERE id = 940101",
                        Integer.class))
                .isEqualTo(1);
    }

    private void migrateCurrentV8(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v6Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("8"));
        assertLatestSuccessfulVersion(jdbcTemplate, "8");

        migrate(harness, dataSource, catalog, null);
        assertV9(jdbcTemplate, catalog);
    }

    private void assertV9(JdbcTemplate jdbcTemplate, String catalog) {
        assertLatestSuccessfulVersion(jdbcTemplate, "9");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'dinner_notifications'",
                        Integer.class,
                        catalog))
                .isEqualTo(11);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_notifications' "
                                + "AND CONSTRAINT_TYPE = 'CHECK' AND ENFORCED = 'YES'",
                        Integer.class,
                        catalog))
                .isGreaterThanOrEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_notifications'",
                        Integer.class,
                        catalog))
                .isZero();

        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 23, 10, 0);
        jdbcTemplate.update(
                "INSERT INTO dinner_notifications "
                        + "(recipient_id, household_id, type, reference_type, reference_id, "
                        + "reference_version, dedupe_key, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                950001L,
                950101L,
                "PARTNER_JOINED",
                "HOUSEHOLD",
                950101L,
                1L,
                "a".repeat(64),
                createdAt,
                createdAt.plusDays(90));
        jdbcTemplate.update(
                "INSERT INTO dinner_notifications "
                        + "(recipient_id, household_id, type, reference_type, reference_id, "
                        + "reference_version, dedupe_key, created_at, expires_at) "
                        + "VALUES (?, NULL, ?, ?, ?, NULL, ?, ?, ?)",
                950002L,
                "MEMBER_REMOVED",
                "HOUSEHOLD_OPERATION",
                950201L,
                "b".repeat(64),
                createdAt,
                createdAt.plusDays(90));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_notifications",
                        Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO dinner_notifications "
                                + "(recipient_id, household_id, type, reference_type, "
                                + "reference_id, dedupe_key, created_at, expires_at) "
                                + "VALUES (1, 2, 'MEMBER_REMOVED', 'HOUSEHOLD', 2, ?, ?, ?)",
                        "c".repeat(64),
                        createdAt,
                        createdAt.plusDays(90)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO dinner_notifications "
                                + "(recipient_id, household_id, type, reference_type, "
                                + "reference_id, dedupe_key, created_at, expires_at) "
                                + "VALUES (1, 2, 'PARTNER_JOINED', 'HOUSEHOLD', 2, ?, ?, ?)",
                        "a".repeat(64),
                        createdAt,
                        createdAt.plusDays(90)))
                .isInstanceOf(DataAccessException.class);
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
