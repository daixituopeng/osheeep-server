package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Guarded MySQL 8 evidence for fresh, production-V4 and current-V10 paths through V11. */
public class DinnerInventoryDeductionMigrationMySqlIT {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Test
    void migratesFreshProductionV4AndCurrentV10CatalogsThroughV11() {
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
            migrateCurrentV10(harness);
        }
    }

    private void migrateFresh(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.freshCatalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        migrate(harness, dataSource, catalog, null);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        assertV11(jdbcTemplate, catalog);
        assertTerminalStateConstraints(jdbcTemplate);
    }

    private void migrateProductionV4(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v4Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("4"));
        insertRecord(
                jdbcTemplate, 1140001L, 1140101L, 1140201L, 1140301L, true);

        migrate(harness, dataSource, catalog, null);

        assertV11(jdbcTemplate, catalog);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT inventory_deduction_status "
                                + "FROM dinner_cooking_records WHERE id = 1140301",
                        String.class))
                .isEqualTo("NOT_APPLICABLE");
    }

    private void migrateCurrentV10(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v6Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("10"));
        assertLatestSuccessfulVersion(jdbcTemplate, "10");
        insertRecord(
                jdbcTemplate, 1150001L, 1150101L, 1150201L, 1150301L, false);

        migrate(harness, dataSource, catalog, null);

        assertV11(jdbcTemplate, catalog);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT inventory_deduction_status "
                                + "FROM dinner_cooking_records WHERE id = 1150301",
                        String.class))
                .isEqualTo("NOT_APPLICABLE");
    }

    private void assertV11(JdbcTemplate jdbcTemplate, String catalog) {
        assertLatestSuccessfulVersion(jdbcTemplate, "11");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_cooking_records' "
                                + "AND COLUMN_NAME IN ('inventory_deduction_status', "
                                + "'inventory_deduction_key', 'inventory_deducted_by', "
                                + "'inventory_deducted_at', 'inventory_deduction_items')",
                        Integer.class,
                        catalog))
                .isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_cooking_records' "
                                + "AND CONSTRAINT_NAME = "
                                + "'ck_dinner_records_inventory_deduction_state' "
                                + "AND CONSTRAINT_TYPE = 'CHECK' AND ENFORCED = 'YES'",
                        Integer.class,
                        catalog))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_cooking_records' "
                                + "AND CONSTRAINT_NAME = "
                                + "'fk_dinner_records_inventory_deducted_by'",
                        Integer.class,
                        catalog))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_cooking_records' "
                                + "AND INDEX_NAME = "
                                + "'uk_dinner_records_inventory_deduction_key' "
                                + "AND NON_UNIQUE = 0",
                        Integer.class,
                        catalog))
                .isEqualTo(1);
    }

    private void assertTerminalStateConstraints(JdbcTemplate jdbcTemplate) {
        insertRecord(
                jdbcTemplate, 1160001L, 1160101L, 1160201L, 1160301L, false);
        insertRecord(
                jdbcTemplate, 1160002L, 1160102L, 1160202L, 1160302L, false);
        insertRecord(
                jdbcTemplate, 1160003L, 1160103L, 1160203L, 1160303L, false);

        jdbcTemplate.update(
                "UPDATE dinner_cooking_records SET "
                        + "inventory_deduction_status = 'PENDING' WHERE id = 1160301");
        jdbcTemplate.update(
                "UPDATE dinner_cooking_records SET "
                        + "inventory_deduction_status = 'APPLIED', "
                        + "inventory_deduction_key = ?, inventory_deducted_by = ?, "
                        + "inventory_deducted_at = ?, inventory_deduction_items = ? "
                        + "WHERE id = 1160302",
                "11000000-0000-4000-8000-000000000001",
                1160002L,
                LocalDateTime.of(2026, 7, 24, 12, 0),
                "[{\"ingredientId\":1,\"quantity\":2,\"unit\":\"个\","
                        + "\"inventoryVersion\":4}]");
        jdbcTemplate.update(
                "UPDATE dinner_cooking_records SET "
                        + "inventory_deduction_status = 'SKIPPED', "
                        + "inventory_deduction_key = ?, inventory_deducted_by = ?, "
                        + "inventory_deducted_at = ?, inventory_deduction_items = JSON_ARRAY() "
                        + "WHERE id = 1160303",
                "11000000-0000-4000-8000-000000000002",
                1160003L,
                LocalDateTime.of(2026, 7, 24, 12, 1));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE dinner_cooking_records SET "
                                + "inventory_deduction_key = ? WHERE id = 1160301",
                        "11000000-0000-4000-8000-000000000003"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE dinner_cooking_records SET "
                                + "inventory_deduction_status = 'SKIPPED', "
                                + "inventory_deduction_key = ?, inventory_deducted_by = ?, "
                                + "inventory_deducted_at = ?, inventory_deduction_items = ? "
                                + "WHERE id = 1160301",
                        "11000000-0000-4000-8000-000000000004",
                        1160001L,
                        LocalDateTime.of(2026, 7, 24, 12, 2),
                        "[{\"ingredientId\":1}]"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE dinner_cooking_records SET "
                                + "inventory_deduction_key = ? WHERE id = 1160303",
                        "11000000-0000-4000-8000-000000000001"))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertRecord(
            JdbcTemplate jdbcTemplate,
            long userId,
            long householdId,
            long menuId,
            long recordId,
            boolean legacyV4
    ) {
        LocalDate recordDate = LocalDate.of(2026, 7, 24);
        LocalDateTime completedAt = LocalDateTime.of(2026, 7, 24, 11, 30);
        jdbcTemplate.update(
                "INSERT INTO users (id, username, display_name, status) "
                        + "VALUES (?, ?, 'migration user', 'ACTIVE')",
                userId,
                "inventory-migration-" + userId);
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) "
                        + "VALUES (?, 'migration household', ?)",
                householdId,
                userId);
        if (legacyV4) {
            jdbcTemplate.update(
                    "INSERT INTO dinner_household_members (household_id, user_id) "
                            + "VALUES (?, ?)",
                    householdId,
                    userId);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO dinner_household_members "
                            + "(household_id, user_id, role, status, seat_no, "
                            + "history_visible_from, version) "
                            + "VALUES (?, ?, 'OWNER', 'ACTIVE', 1, ?, 1)",
                    householdId,
                    userId,
                    LocalDateTime.of(1970, 1, 1, 0, 0));
        }
        jdbcTemplate.update(
                "INSERT INTO dinner_menus "
                        + "(id, household_id, menu_date, status, version, "
                        + "completed_by, completed_at) "
                        + "VALUES (?, ?, ?, 'COMPLETED', 1, ?, ?)",
                menuId,
                householdId,
                recordDate,
                userId,
                completedAt);
        jdbcTemplate.update(
                "INSERT INTO dinner_cooking_records "
                        + "(id, household_id, menu_id, record_date, "
                        + "completed_by, completed_at) VALUES (?, ?, ?, ?, ?, ?)",
                recordId,
                householdId,
                menuId,
                recordDate,
                userId,
                completedAt);
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
        harness.requireActiveCatalog(flyway.getConfiguration().getDataSource(), catalog);
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
