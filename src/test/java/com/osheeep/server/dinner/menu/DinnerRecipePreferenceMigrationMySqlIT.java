package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Guarded MySQL 8 evidence for fresh, production-V4 and current-V11 paths through V12. */
public class DinnerRecipePreferenceMigrationMySqlIT {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Test
    void migratesFreshProductionV4AndCurrentV11CatalogsThroughV12() {
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
            migrateCurrentV11(harness);
        }
    }

    private void migrateFresh(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.freshCatalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        migrate(harness, dataSource, catalog, null);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        assertV12(jdbcTemplate, catalog);
        assertPreferenceConstraints(jdbcTemplate);
    }

    private void migrateProductionV4(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v4Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("4"));
        jdbcTemplate.update(
                "INSERT INTO users (id, username, display_name, status) "
                        + "VALUES (1240001, 'preference-v4', 'V4 user', 'ACTIVE')");
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) "
                        + "VALUES (1240101, 'V4 household', 1240001)");
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members (household_id, user_id) "
                        + "VALUES (1240101, 1240001)");

        migrate(harness, dataSource, catalog, null);

        assertV12(jdbcTemplate, catalog);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_household_members "
                                + "WHERE household_id = 1240101 "
                                + "AND role = 'OWNER' AND status = 'ACTIVE' "
                                + "AND seat_no = 1 AND version = 1",
                        Integer.class))
                .isEqualTo(1);
    }

    private void migrateCurrentV11(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.v6Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("11"));
        assertLatestSuccessfulVersion(jdbcTemplate, "11");

        migrate(harness, dataSource, catalog, null);

        assertV12(jdbcTemplate, catalog);
    }

    private void assertV12(JdbcTemplate jdbcTemplate, String catalog) {
        assertLatestSuccessfulVersion(jdbcTemplate, "12");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_recipe_preferences'",
                        Integer.class,
                        catalog))
                .isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_recipe_preferences'",
                        Integer.class,
                        catalog))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_recipe_preferences' "
                                + "AND CONSTRAINT_TYPE = 'CHECK' AND ENFORCED = 'YES'",
                        Integer.class,
                        catalog))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_recipe_preferences' "
                                + "AND INDEX_NAME = "
                                + "'uk_dinner_recipe_preferences_membership_recipe' "
                                + "AND NON_UNIQUE = 0",
                        Integer.class,
                        catalog))
                .isEqualTo(2);
    }

    private void assertPreferenceConstraints(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, display_name, status) "
                        + "VALUES (1260001, 'preference-user', 'Preference user', 'ACTIVE')");
        jdbcTemplate.update(
                "INSERT INTO dinner_households (id, name, created_by) "
                        + "VALUES (1260101, 'Preference household', 1260001)");
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(id, household_id, user_id, role, status, seat_no, "
                        + "history_visible_from, version) "
                        + "VALUES (1260201, 1260101, 1260001, 'OWNER', 'ACTIVE', 1, ?, 1)",
                LocalDateTime.of(1970, 1, 1, 0, 0));
        Long recipeId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM dinner_recipes WHERE scope = 'SYSTEM' "
                        + "AND status = 'PUBLISHED'",
                Long.class);
        assertThat(recipeId).isNotNull();

        jdbcTemplate.update(
                "INSERT INTO dinner_recipe_preferences "
                        + "(household_id, membership_id, user_id, recipe_id, "
                        + "preference, version) VALUES (1260101, 1260201, 1260001, ?, 'LIKE', 1)",
                recipeId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO dinner_recipe_preferences "
                                + "(household_id, membership_id, user_id, recipe_id, "
                                + "preference, version) "
                                + "VALUES (1260101, 1260201, 1260001, ?, 'NEUTRAL', 1)",
                        recipeId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE dinner_recipe_preferences SET preference = 'LOVE' "
                                + "WHERE membership_id = 1260201"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE dinner_recipe_preferences SET version = 0 "
                                + "WHERE membership_id = 1260201"))
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
        String localAuthentication =
                "127.0.0.1".equals(host) || "localhost".equals(host)
                        ? "&allowPublicKeyRetrieval=true"
                        : "";
        return "jdbc:mysql://"
                + host
                + ":"
                + System.getenv("OSHEEEP_DB_PORT")
                + "/"
                + System.getenv("OSHEEEP_DB_NAME")
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&serverTimezone=Asia/Shanghai"
                + localAuthentication;
    }
}
