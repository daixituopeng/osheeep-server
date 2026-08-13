package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.osheeep.server.OsheeepServerApplication;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.cooking.DinnerCookingService;
import com.osheeep.server.dinner.cooking.dto.AddCookingDishRequest;
import com.osheeep.server.dinner.cooking.dto.CookingSessionResponse;
import com.osheeep.server.dinner.cooking.dto.StartCookingRequest;
import com.osheeep.server.dinner.cooking.dto.UpdateCookingDishCompletionRequest;
import com.osheeep.server.dinner.recipe.DinnerCustomRecipeFlywayMigrationStrategy;
import com.osheeep.server.dinner.record.DinnerRecordService;
import com.osheeep.server.dinner.record.dto.CompleteMenuResponse;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Guarded MySQL 8 acceptance coverage for V15 and the cooking transaction boundaries.
 *
 * <p>This class is intentionally named {@code *MySqlIT}, so an ordinary {@code mvn test} does not
 * select it. It must be run explicitly with a loopback-only disposable base catalog whose raw
 * {@code OSHEEEP_DB_NAME} and {@code OSHEEEP_DB_TEST_NAME} values match, together with
 * {@code OSHEEEP_ALLOW_EPHEMERAL_DATABASES=true}. The harness creates UUID-scoped child catalogs
 * and drops them in {@link AutoCloseable#close()}.</p>
 */
public class DinnerCookingV15MySqlIT {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String SNAPSHOT_FAILURE_TRIGGER =
            "dinner_cooking_v15_snapshot_failure";

    @Test
    @Timeout(240)
    void migratesV15AndPreservesCookingConcurrencyAndRollbackInvariants()
            throws Exception {
        String baseJdbcUrl = effectiveJdbcUrl();
        DataSource baseDataSource = new DriverManagerDataSource(
                baseJdbcUrl,
                System.getenv("OSHEEEP_DB_USERNAME"),
                System.getenv("OSHEEEP_DB_PASSWORD"));
        List<String> generatedCatalogs = new ArrayList<>();

        try (DinnerEphemeralCatalogHarness harness =
                DinnerEphemeralCatalogHarness.fromEnvironment(baseDataSource, baseJdbcUrl)) {
            // The existing harness has three exact UUID-scoped slots. Their historical v4/v6
            // names are only suffixes; this test uses them for V13 upgrade and business coverage.
            harness.createCatalog(harness.freshCatalog());
            harness.createCatalog(harness.v4Catalog());
            harness.createCatalog(harness.v6Catalog());
            generatedCatalogs.addAll(List.of(
                    harness.freshCatalog(), harness.v4Catalog(), harness.v6Catalog()));

            verifyFreshMigration(harness);
            verifyProductionShapedV13Migration(harness);
            verifyRealBusinessTransactions(harness, baseJdbcUrl);

            assertThat(harness.createdCatalogs())
                    .containsExactlyInAnyOrderElementsOf(generatedCatalogs);
        }

        assertGeneratedCatalogsWereRemoved(baseDataSource, generatedCatalogs);
    }

    private void verifyFreshMigration(DinnerEphemeralCatalogHarness harness) {
        String catalog = harness.freshCatalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        migrate(harness, dataSource, catalog, null);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertLatestSuccessfulVersion(jdbc, "15");
        assertV15Schema(jdbc, catalog);
    }

    private void verifyProductionShapedV13Migration(
            DinnerEphemeralCatalogHarness harness
    ) {
        String catalog = harness.v4Catalog();
        DataSource dataSource = harness.dataSourceFor(catalog);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        migrate(harness, dataSource, catalog, MigrationVersion.fromVersion("13"));
        assertLatestSuccessfulVersion(jdbc, "13");
        long legacySnapshotId = insertProductionShapedV13Record(jdbc);

        migrate(harness, dataSource, catalog, null);

        assertLatestSuccessfulVersion(jdbc, "15");
        assertV15Schema(jdbc, catalog);
        assertThat(jdbc.queryForObject(
                        "SELECT origin FROM dinner_record_dish_snapshots WHERE id = ?",
                        String.class,
                        legacySnapshotId))
                .isEqualTo("PLANNED");
    }

    private void verifyRealBusinessTransactions(
            DinnerEphemeralCatalogHarness harness,
            String baseJdbcUrl
    ) throws Exception {
        String catalog = harness.v6Catalog();
        DataSource migrationDataSource = harness.dataSourceFor(catalog);
        migrate(harness, migrationDataSource, catalog, null);
        assertLatestSuccessfulVersion(new JdbcTemplate(migrationDataSource), "15");

        String businessJdbcUrl = jdbcUrlForCatalog(baseJdbcUrl, catalog);
        try (ConfigurableApplicationContext context = startBusinessContext(
                harness, catalog, businessJdbcUrl)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            DinnerCookingService cookingService =
                    context.getBean(DinnerCookingService.class);
            DinnerRecordService recordService =
                    context.getBean(DinnerRecordService.class);
            List<Long> recipeIds = selectableSystemRecipeIds(jdbc, 6);

            verifyConcurrentAddVersionConflict(
                    jdbc, cookingService, recipeIds.subList(0, 3));
            verifyConcurrentCompletionVersionConflict(
                    jdbc, cookingService, recipeIds.subList(0, 2));
            verifyConcurrentEndCreatesOneRecord(
                    jdbc, cookingService, recordService, recipeIds.get(0));
            verifySnapshotFailureRollsBackEverything(
                    jdbc, cookingService, recordService, recipeIds.get(1));
            verifyLegacyConfirmedCompletionDoesNotCreateCookingRows(
                    jdbc, recordService, recipeIds.get(2));
        }
    }

    private void verifyConcurrentAddVersionConflict(
            JdbcTemplate jdbc,
            DinnerCookingService service,
            List<Long> recipeIds
    ) throws Exception {
        BusinessFixture fixture = seedConfirmedFixture(
                jdbc, List.of(recipeIds.getFirst()), "concurrent_add");
        CookingSessionResponse started = service.start(
                fixture.firstUserId(),
                new StartCookingRequest(fixture.menuVersion(), uuid()));

        List<Outcome<CookingSessionResponse>> outcomes = concurrently(
                () -> service.addDish(
                        fixture.firstUserId(),
                        new AddCookingDishRequest(
                                recipeIds.get(1), null, started.version(), uuid())),
                () -> service.addDish(
                        fixture.secondUserId(),
                        new AddCookingDishRequest(
                                recipeIds.get(2), null, started.version(), uuid())));

        assertOneSuccessAndVersionConflict(outcomes);
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_menu_cooking_dishes WHERE menu_id = ?",
                        fixture.menuId()))
                .isEqualTo(2);
        assertThat(value(jdbc,
                        "SELECT version FROM dinner_menus WHERE id = ?",
                        Long.class,
                        fixture.menuId()))
                .isEqualTo(started.version() + 1);
    }

    private void verifyConcurrentCompletionVersionConflict(
            JdbcTemplate jdbc,
            DinnerCookingService service,
            List<Long> recipeIds
    ) throws Exception {
        BusinessFixture fixture = seedConfirmedFixture(
                jdbc, recipeIds, "concurrent_completion");
        CookingSessionResponse started = service.start(
                fixture.firstUserId(),
                new StartCookingRequest(fixture.menuVersion(), uuid()));
        assertThat(started.dishes()).hasSize(2);

        List<Outcome<CookingSessionResponse>> outcomes = concurrently(
                () -> service.setCompleted(
                        fixture.firstUserId(),
                        started.dishes().get(0).id(),
                        new UpdateCookingDishCompletionRequest(true, started.version())),
                () -> service.setCompleted(
                        fixture.secondUserId(),
                        started.dishes().get(1).id(),
                        new UpdateCookingDishCompletionRequest(true, started.version())));

        assertOneSuccessAndVersionConflict(outcomes);
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_menu_cooking_dishes "
                                + "WHERE menu_id = ? AND completed_at IS NOT NULL",
                        fixture.menuId()))
                .isEqualTo(1);
        assertThat(value(jdbc,
                        "SELECT version FROM dinner_menus WHERE id = ?",
                        Long.class,
                        fixture.menuId()))
                .isEqualTo(started.version() + 1);
    }

    private void verifyConcurrentEndCreatesOneRecord(
            JdbcTemplate jdbc,
            DinnerCookingService cookingService,
            DinnerRecordService recordService,
            Long recipeId
    ) throws Exception {
        BusinessFixture fixture = seedConfirmedFixture(
                jdbc, List.of(recipeId), "concurrent_end");
        CookingSessionResponse started = cookingService.start(
                fixture.firstUserId(),
                new StartCookingRequest(fixture.menuVersion(), uuid()));
        CookingSessionResponse completedDish = cookingService.setCompleted(
                fixture.firstUserId(),
                started.dishes().getFirst().id(),
                new UpdateCookingDishCompletionRequest(true, started.version()));

        List<Outcome<CompleteMenuResponse>> outcomes = concurrently(
                () -> recordService.complete(
                        fixture.firstUserId(), completedDish.version(), uuid()),
                () -> recordService.complete(
                        fixture.secondUserId(), completedDish.version(), uuid()));

        assertThat(outcomes.stream().filter(Outcome::succeeded).count())
                .isBetween(1L, 2L);
        outcomes.stream()
                .map(Outcome::failure)
                .filter(Objects::nonNull)
                .forEach(this::assertVersionConflict);
        CompleteMenuResponse winner = outcomes.stream()
                .filter(Outcome::succeeded)
                .map(Outcome::value)
                .findFirst()
                .orElseThrow();
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_cooking_records WHERE menu_id = ?",
                        fixture.menuId()))
                .isEqualTo(1);
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_record_dish_snapshots s "
                                + "JOIN dinner_cooking_records r ON r.id = s.record_id "
                                + "WHERE r.menu_id = ?",
                        fixture.menuId()))
                .isEqualTo(1);

        CompleteMenuResponse replay = recordService.complete(
                fixture.secondUserId(), completedDish.version(), uuid());
        assertThat(replay.recordId()).isEqualTo(winner.recordId());
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_menu_actions "
                                + "WHERE menu_id = ? AND action_type = 'COMPLETE'",
                        fixture.menuId()))
                .isEqualTo(1);
    }

    private void verifySnapshotFailureRollsBackEverything(
            JdbcTemplate jdbc,
            DinnerCookingService cookingService,
            DinnerRecordService recordService,
            Long recipeId
    ) {
        BusinessFixture fixture = seedConfirmedFixture(
                jdbc, List.of(recipeId), "snapshot_rollback");
        CookingSessionResponse started = cookingService.start(
                fixture.firstUserId(),
                new StartCookingRequest(fixture.menuVersion(), uuid()));
        CookingSessionResponse completedDish = cookingService.setCompleted(
                fixture.firstUserId(),
                started.dishes().getFirst().id(),
                new UpdateCookingDishCompletionRequest(true, started.version()));

        jdbc.execute("DROP TRIGGER IF EXISTS " + SNAPSHOT_FAILURE_TRIGGER);
        jdbc.execute("CREATE TRIGGER " + SNAPSHOT_FAILURE_TRIGGER
                + " BEFORE INSERT ON dinner_record_dish_snapshots FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT = 'forced V15 snapshot failure'");
        try {
            assertThatThrownBy(() -> recordService.complete(
                            fixture.firstUserId(), completedDish.version(), uuid()))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + SNAPSHOT_FAILURE_TRIGGER);
        }

        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_cooking_records WHERE menu_id = ?",
                        fixture.menuId()))
                .isZero();
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_record_dish_snapshots s "
                                + "JOIN dinner_cooking_records r ON r.id = s.record_id "
                                + "WHERE r.menu_id = ?",
                        fixture.menuId()))
                .isZero();
        assertThat(value(jdbc,
                        "SELECT status FROM dinner_menus WHERE id = ?",
                        String.class,
                        fixture.menuId()))
                .isEqualTo("COOKING");
        assertThat(value(jdbc,
                        "SELECT version FROM dinner_menus WHERE id = ?",
                        Long.class,
                        fixture.menuId()))
                .isEqualTo(completedDish.version());
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_menu_actions "
                                + "WHERE menu_id = ? AND action_type = 'COMPLETE'",
                        fixture.menuId()))
                .isZero();
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_menu_cooking_dishes "
                                + "WHERE menu_id = ? AND completed_at IS NOT NULL",
                        fixture.menuId()))
                .isEqualTo(1);
    }

    private void verifyLegacyConfirmedCompletionDoesNotCreateCookingRows(
            JdbcTemplate jdbc,
            DinnerRecordService service,
            Long recipeId
    ) {
        BusinessFixture fixture = seedConfirmedFixture(
                jdbc, List.of(recipeId), "legacy_complete");

        CompleteMenuResponse response = service.complete(
                fixture.firstUserId(), fixture.menuVersion(), uuid());

        assertThat(response.recordId()).isPositive();
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_menu_cooking_dishes WHERE menu_id = ?",
                        fixture.menuId()))
                .isZero();
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM dinner_cooking_records WHERE menu_id = ?",
                        fixture.menuId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT s.origin FROM dinner_record_dish_snapshots s "
                                + "JOIN dinner_cooking_records r ON r.id = s.record_id "
                                + "WHERE r.menu_id = ? ORDER BY s.sort_order",
                        String.class,
                        fixture.menuId()))
                .containsExactly("PLANNED");
        assertThat(value(jdbc,
                        "SELECT status FROM dinner_menus WHERE id = ?",
                        String.class,
                        fixture.menuId()))
                .isEqualTo("COMPLETED");
    }

    private ConfigurableApplicationContext startBusinessContext(
            DinnerEphemeralCatalogHarness harness,
            String catalog,
            String jdbcUrl
    ) {
        if (!harness.createdCatalogs().contains(catalog)) {
            throw unsafeBusinessCatalog();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", jdbcUrl);
        properties.put("spring.datasource.username", System.getenv("OSHEEEP_DB_USERNAME"));
        properties.put("spring.datasource.password", System.getenv("OSHEEEP_DB_PASSWORD"));
        properties.put("spring.datasource.hikari.maximum-pool-size", "8");
        properties.put("spring.datasource.hikari.minimum-idle", "0");
        properties.put("spring.flyway.enabled", "true");
        properties.put("spring.flyway.locations", MIGRATION_LOCATION);
        properties.put("spring.flyway.default-schema", catalog);
        properties.put("spring.flyway.schemas", catalog);
        properties.put("spring.flyway.create-schemas", "false");
        properties.put("osheeep.test.expected-catalog", catalog);
        properties.put("osheeep.jwt.issuer", "dinner-cooking-v15-it");
        properties.put(
                "osheeep.jwt.secret",
                "dinner-cooking-v15-it-secret-at-least-32-bytes");
        properties.put("osheeep.jwt.access-token-ttl-minutes", "120");
        properties.put("osheeep.wechat.app-id", "local-it");
        properties.put("osheeep.wechat.app-secret", "local-it");
        properties.put("osheeep.wechat.subscription.enabled", "false");
        properties.put(
                "osheeep.dinner.invite-secret",
                "dinner-cooking-v15-it-invite-secret");
        properties.put("osheeep.dinner.images.public-base-url", "http://127.0.0.1:8080");
        properties.put("spring.task.scheduling.enabled", "false");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "WARN");
        properties.put(
                "spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");

        String[] commandLineProperties = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(
                        OsheeepServerApplication.class,
                        BusinessCatalogConfiguration.class)
                .profiles("local")
                .web(WebApplicationType.NONE)
                .initializers(context -> requireSafeBusinessTarget(
                        context.getEnvironment(), harness, catalog, jdbcUrl))
                .run(commandLineProperties);
    }

    private void requireSafeBusinessTarget(
            Environment environment,
            DinnerEphemeralCatalogHarness harness,
            String catalog,
            String jdbcUrl
    ) {
        if (!"true".equals(System.getenv("OSHEEEP_ALLOW_EPHEMERAL_DATABASES"))
                || !Objects.equals(
                        System.getenv("OSHEEEP_DB_NAME"),
                        System.getenv("OSHEEEP_DB_TEST_NAME"))
                || !harness.createdCatalogs().contains(catalog)
                || !Objects.equals(jdbcUrl, environment.getProperty("spring.datasource.url"))
                || !Objects.equals(
                        catalog,
                        environment.getProperty("osheeep.test.expected-catalog"))
                || environment.getProperty("spring.flyway.url") != null) {
            throw unsafeBusinessCatalog();
        }
        URI target = mysqlUri(jdbcUrl);
        if (target == null
                || !isLoopback(target.getHost())
                || !Objects.equals("/" + catalog, target.getPath())) {
            throw unsafeBusinessCatalog();
        }
    }

    private BusinessFixture seedConfirmedFixture(
            JdbcTemplate jdbc,
            List<Long> recipeIds,
            String label
    ) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long firstUserId = insertReturningId(
                jdbc,
                "INSERT INTO users "
                        + "(username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE')",
                label + "_owner_" + suffix,
                label + "_owner_" + suffix + "@example.test",
                "not-used",
                "owner");
        long secondUserId = insertReturningId(
                jdbc,
                "INSERT INTO users "
                        + "(username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE')",
                label + "_member_" + suffix,
                label + "_member_" + suffix + "@example.test",
                "not-used",
                "member");
        long householdId = insertReturningId(
                jdbc,
                "INSERT INTO dinner_households "
                        + "(name, timezone, status, version, invite_revision, created_by) "
                        + "VALUES (?, 'Asia/Shanghai', 'ACTIVE', 1, 0, ?)",
                label + "_household_" + suffix,
                firstUserId);
        LocalDateTime historyStart = LocalDateTime.of(1970, 1, 1, 0, 0);
        jdbc.update(
                "INSERT INTO dinner_household_members "
                        + "(household_id, user_id, role, status, seat_no, "
                        + "history_visible_from, version) "
                        + "VALUES (?, ?, 'OWNER', 'ACTIVE', 1, ?, 1)",
                householdId,
                firstUserId,
                historyStart);
        jdbc.update(
                "INSERT INTO dinner_household_members "
                        + "(household_id, user_id, role, status, seat_no, "
                        + "history_visible_from, version) "
                        + "VALUES (?, ?, 'MEMBER', 'ACTIVE', 2, ?, 1)",
                householdId,
                secondUserId,
                historyStart);

        LocalDate menuDate = new BusinessDateResolver().resolve(
                "Asia/Shanghai", Instant.now());
        long menuId = insertReturningId(
                jdbc,
                "INSERT INTO dinner_menus "
                        + "(household_id, menu_date, status, version, confirmed_by, confirmed_at) "
                        + "VALUES (?, ?, 'CONFIRMED', 1, ?, UTC_TIMESTAMP(3))",
                householdId,
                menuDate,
                firstUserId);
        for (int index = 0; index < recipeIds.size(); index++) {
            jdbc.update(
                    "INSERT INTO dinner_menu_selections "
                            + "(menu_id, user_id, recipe_id, recipe_version, method_id) "
                            + "VALUES (?, ?, ?, 1, NULL)",
                    menuId,
                    index % 2 == 0 ? firstUserId : secondUserId,
                    recipeIds.get(index));
        }
        return new BusinessFixture(
                firstUserId, secondUserId, householdId, menuId, 1L);
    }

    private long insertProductionShapedV13Record(JdbcTemplate jdbc) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long userId = insertReturningId(
                jdbc,
                "INSERT INTO users "
                        + "(username, email, password_hash, display_name, status) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE')",
                "v13_user_" + suffix,
                "v13_" + suffix + "@example.test",
                "not-used",
                "V13 user");
        long householdId = insertReturningId(
                jdbc,
                "INSERT INTO dinner_households "
                        + "(name, timezone, status, version, invite_revision, created_by) "
                        + "VALUES (?, 'Asia/Shanghai', 'ACTIVE', 1, 0, ?)",
                "v13_household_" + suffix,
                userId);
        jdbc.update(
                "INSERT INTO dinner_household_members "
                        + "(household_id, user_id, role, status, seat_no, "
                        + "history_visible_from, version) "
                        + "VALUES (?, ?, 'OWNER', 'ACTIVE', 1, '1970-01-01', 1)",
                householdId,
                userId);
        long menuId = insertReturningId(
                jdbc,
                "INSERT INTO dinner_menus "
                        + "(household_id, menu_date, status, version, confirmed_by, confirmed_at, "
                        + "completed_by, completed_at) "
                        + "VALUES (?, '2026-08-01', 'COMPLETED', 2, ?, '2026-08-01 10:00:00', "
                        + "?, '2026-08-01 11:00:00')",
                householdId,
                userId,
                userId);
        long recordId = insertReturningId(
                jdbc,
                "INSERT INTO dinner_cooking_records "
                        + "(household_id, menu_id, record_date, completed_by, completed_at, "
                        + "inventory_deduction_status) "
                        + "VALUES (?, ?, '2026-08-01', ?, '2026-08-01 11:00:00', 'PENDING')",
                householdId,
                menuId,
                userId);
        Long recipeId = value(
                jdbc,
                "SELECT MIN(id) FROM dinner_recipes WHERE scope = 'SYSTEM'",
                Long.class);
        return insertReturningId(
                jdbc,
                "INSERT INTO dinner_record_dish_snapshots "
                        + "(record_id, recipe_id, recipe_scope, recipe_version, name, image_path, "
                        + "category, flavor, estimated_minutes, servings, method_id, method_name, "
                        + "cooking_style, method_steps, ingredients, selected_by_user_ids, "
                        + "sort_order) VALUES (?, ?, 'SYSTEM', 1, 'V13 snapshot', NULL, "
                        + "'家常菜', '清淡', 10, NULL, NULL, NULL, NULL, JSON_ARRAY(), "
                        + "JSON_ARRAY(JSON_OBJECT('ingredientId', 1, 'name', '测试食材', "
                        + "'quantity', 1, 'unit', '个', 'required', TRUE, 'sortOrder', 0)), "
                        + "JSON_ARRAY(?), 0)",
                recordId,
                recipeId,
                userId);
    }

    private List<Long> selectableSystemRecipeIds(JdbcTemplate jdbc, int count) {
        List<Long> recipeIds = jdbc.queryForList(
                "SELECT r.id FROM dinner_recipes r "
                        + "WHERE r.scope = 'SYSTEM' AND r.status = 'PUBLISHED' "
                        + "AND EXISTS (SELECT 1 FROM dinner_recipe_ingredients ri "
                        + "WHERE ri.recipe_id = r.id AND ri.is_required = 1) "
                        + "ORDER BY r.id LIMIT ?",
                Long.class,
                count);
        assertThat(recipeIds).hasSize(count);
        return recipeIds;
    }

    private <T> List<Outcome<T>> concurrently(
            Callable<T> first,
            Callable<T> second
    ) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome<T>> firstFuture = executor.submit(
                    () -> outcomeAfterBarrier(barrier, first));
            Future<Outcome<T>> secondFuture = executor.submit(
                    () -> outcomeAfterBarrier(barrier, second));
            return List.of(
                    firstFuture.get(30, TimeUnit.SECONDS),
                    secondFuture.get(30, TimeUnit.SECONDS));
        }
    }

    private <T> Outcome<T> outcomeAfterBarrier(
            CyclicBarrier barrier,
            Callable<T> action
    ) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return new Outcome<>(action.call(), null);
        } catch (Throwable failure) {
            return new Outcome<>(null, failure);
        }
    }

    private void assertOneSuccessAndVersionConflict(List<? extends Outcome<?>> outcomes) {
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() == null).count())
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() != null).count())
                .isEqualTo(1);
        Throwable failure = outcomes.stream()
                .filter(outcome -> outcome.failure() != null)
                .map(Outcome::failure)
                .findFirst()
                .orElseThrow();
        assertVersionConflict(failure);
    }

    private void assertVersionConflict(Throwable failure) {
        assertThat(rootCause(failure))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_MENU_VERSION_CONFLICT));
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
                .createSchemas(false)
                .cleanDisabled(true);
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

    private void assertV15Schema(JdbcTemplate jdbc, String catalog) {
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM information_schema.TABLES "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_menu_cooking_dishes'",
                        catalog))
                .isEqualTo(1);
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_record_dish_snapshots' "
                                + "AND COLUMN_NAME = 'origin' AND IS_NULLABLE = 'NO' "
                                + "AND COLUMN_DEFAULT = 'PLANNED'",
                        catalog))
                .isEqualTo(1);
        assertUniqueIndex(jdbc, catalog, "menu_id,recipe_id");
        assertUniqueIndex(jdbc, catalog, "menu_id,sort_order");
        assertUniqueIndex(jdbc, catalog, "add_idempotency_key");
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_menu_cooking_dishes' "
                                + "AND REFERENCED_TABLE_NAME = 'dinner_menus'",
                        catalog))
                .isEqualTo(1);
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_menu_cooking_dishes' "
                                + "AND CONSTRAINT_TYPE = 'CHECK'",
                        catalog))
                .isGreaterThanOrEqualTo(4);
    }

    private void assertUniqueIndex(
            JdbcTemplate jdbc,
            String catalog,
            String columns
    ) {
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM ("
                                + "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME "
                                + "ORDER BY SEQ_IN_INDEX SEPARATOR ',') indexed_columns "
                                + "FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = ? "
                                + "AND TABLE_NAME = 'dinner_menu_cooking_dishes' "
                                + "AND NON_UNIQUE = 0 GROUP BY INDEX_NAME) indexes_for_table "
                                + "WHERE indexed_columns = ?",
                        catalog,
                        columns))
                .isEqualTo(1);
    }

    private void assertLatestSuccessfulVersion(JdbcTemplate jdbc, String version) {
        assertThat(value(
                        jdbc,
                        "SELECT version FROM flyway_schema_history "
                                + "WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1",
                        String.class))
                .isEqualTo(version);
        assertThat(count(jdbc,
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0"))
                .isZero();
    }

    private void assertGeneratedCatalogsWereRemoved(
            DataSource baseDataSource,
            List<String> catalogs
    ) {
        JdbcTemplate jdbc = new JdbcTemplate(baseDataSource);
        for (String catalog : catalogs) {
            assertThat(count(jdbc,
                            "SELECT COUNT(*) FROM information_schema.SCHEMATA "
                                    + "WHERE SCHEMA_NAME = ?",
                            catalog))
                    .as("ephemeral catalog %s should be removed", catalog)
                    .isZero();
        }
    }

    private long insertReturningId(
            JdbcTemplate jdbc,
            String sql,
            Object... arguments
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> preparedStatement(
                connection, sql, arguments), keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("generated key for fixture insert").isNotNull();
        return key.longValue();
    }

    private PreparedStatement preparedStatement(
            Connection connection,
            String sql,
            Object[] arguments
    ) throws java.sql.SQLException {
        PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS);
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
        return statement;
    }

    private long count(JdbcTemplate jdbc, String sql, Object... arguments) {
        Long result = jdbc.queryForObject(sql, Long.class, arguments);
        assertThat(result).isNotNull();
        return result;
    }

    private <T> T value(
            JdbcTemplate jdbc,
            String sql,
            Class<T> type,
            Object... arguments
    ) {
        return jdbc.queryForObject(sql, type, arguments);
    }

    private Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private String uuid() {
        return UUID.randomUUID().toString();
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
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }

    private String jdbcUrlForCatalog(String baseJdbcUrl, String catalog) {
        URI base = mysqlUri(baseJdbcUrl);
        String expectedBasePath = "/" + System.getenv("OSHEEEP_DB_TEST_NAME");
        if (base == null || !Objects.equals(expectedBasePath, base.getPath())) {
            throw unsafeBusinessCatalog();
        }
        int queryStart = baseJdbcUrl.indexOf('?');
        String query = queryStart < 0 ? "" : baseJdbcUrl.substring(queryStart);
        String withoutQuery = queryStart < 0
                ? baseJdbcUrl : baseJdbcUrl.substring(0, queryStart);
        int finalSlash = withoutQuery.lastIndexOf('/');
        if (finalSlash < 0) {
            throw unsafeBusinessCatalog();
        }
        return withoutQuery.substring(0, finalSlash + 1) + catalog + query;
    }

    private URI mysqlUri(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) {
            return null;
        }
        try {
            return new URI(jdbcUrl.substring("jdbc:".length()));
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    private IllegalStateException unsafeBusinessCatalog() {
        return new IllegalStateException(
                "Dinner cooking V15 IT requires a tracked loopback ephemeral catalog");
    }

    private record BusinessFixture(
            Long firstUserId,
            Long secondUserId,
            Long householdId,
            Long menuId,
            Long menuVersion
    ) {}

    private record Outcome<T>(T value, Throwable failure) {
        private boolean succeeded() {
            return failure == null;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BusinessCatalogConfiguration {

        @Bean
        FlywayMigrationStrategy dinnerCookingV15FlywayMigrationStrategy(
                @Value("${osheeep.test.expected-catalog}") String expectedCatalog
        ) {
            return new DinnerCustomRecipeFlywayMigrationStrategy(expectedCatalog);
        }
    }
}
