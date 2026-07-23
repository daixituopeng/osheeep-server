package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import com.osheeep.server.dinner.recipe.DinnerCustomRecipeFlywayMigrationStrategy;
import com.osheeep.server.user.AccountDeletionTransaction;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL acceptance coverage for the transaction guarantees that mocks cannot prove.
 *
 * <p>The safety initializer refuses to start unless the local profile points at the explicit
 * disposable database selected by both {@code OSHEEEP_DB_NAME} and
 * {@code OSHEEEP_DB_TEST_NAME}.</p>
 */
@ActiveProfiles("local")
@SpringBootTest
@ContextConfiguration(
        initializers = DinnerMembershipTerminationTestDatabaseSafetyInitializer.class)
@Import(DinnerMembershipTerminationMySqlIT.FlywaySafetyConfiguration.class)
class DinnerMembershipTerminationMySqlIT {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DinnerHouseholdOperationService operationService;
    @Autowired private DinnerHouseholdWriteService writeService;
    @Autowired private DinnerHouseholdDissolutionTransaction dissolutionTransaction;
    @Autowired private DinnerAccountCleanupService accountCleanupService;
    @Autowired private AccountDeletionTransaction accountDeletionTransaction;
    @Autowired private HouseholdOperationFingerprinter fingerprinter;
    @Autowired private TransactionTemplate transactionTemplate;
    @MockitoSpyBean private DinnerHouseholdMapper householdMapper;
    @MockitoSpyBean private DinnerHouseholdOperationMapper operationMapper;
    @MockitoSpyBean private DinnerHouseholdMemberMapper memberMapper;

    private Long ownerUserId;
    private Long memberUserId;
    private Long householdId;
    private Long ownerMembershipId;
    private Long memberMembershipId;
    private Long inviteId;
    private Long menuId;
    private Long draftRecipeId;
    private Long systemSourceRecipeId;
    private Long householdIngredientId;
    private Long inventoryId;
    private String suffix;

    @BeforeEach
    void seedDedicatedV8Database() {
        reset(householdMapper, operationMapper, memberMapper);
        requireDedicatedV8Database();

        suffix = UUID.randomUUID().toString().replace("-", "");
        ownerUserId = insertUser("termination_owner_" + suffix);
        memberUserId = insertUser("termination_member_" + suffix);
        householdId = insertHousehold();
        ownerMembershipId = insertMembership(ownerUserId, "OWNER", 1);
        memberMembershipId = insertMembership(memberUserId, "MEMBER", 2);
        inviteId = insertOpenInvite();
        systemSourceRecipeId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM dinner_recipes WHERE scope = 'SYSTEM'",
                Long.class);
        assertThat(systemSourceRecipeId).as("a retained system source fixture").isNotNull();
        householdIngredientId = insertHouseholdIngredient();
        draftRecipeId = insertMemberDraft();
        insertDraftIngredient();
        inventoryId = insertInventory();
        menuId = insertConfirmedMenu();
        insertMemberSelection();
    }

    @AfterEach
    void deleteOnlySeededRows() {
        reset(householdMapper, operationMapper, memberMapper);
        if (suffix == null) {
            return;
        }
        requireDedicatedCatalogAtRuntime();
        if (householdId != null) {
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_operations WHERE household_id = ?",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_menu_selections WHERE menu_id IN "
                            + "(SELECT id FROM dinner_menus WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update("DELETE FROM dinner_menus WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_recipe_ingredients WHERE recipe_id IN "
                            + "(SELECT id FROM dinner_recipes WHERE household_id = ? "
                            + "OR (household_id IS NULL AND creator_id IN (?, ?)))",
                    householdId,
                    ownerUserId,
                    memberUserId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_inventory WHERE household_id = ?",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_recipes WHERE household_id = ? "
                            + "OR (household_id IS NULL AND creator_id IN (?, ?))",
                    householdId,
                    ownerUserId,
                    memberUserId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_ingredients WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_invite_codes WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_members WHERE household_id = ?", householdId);
            jdbcTemplate.update("DELETE FROM dinner_households WHERE id = ?", householdId);
        }
        jdbcTemplate.update(
                "DELETE FROM wechat_user_identities WHERE user_id IN (?, ?)",
                ownerUserId,
                memberUserId);
        if (memberUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberUserId);
        }
        if (ownerUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerUserId);
        }
    }

    @Test
    @Timeout(20)
    void concurrentOuterMissesSerializeOnActorAndSecondRequestReplays() throws Exception {
        String key = UUID.randomUUID().toString();
        CyclicBarrier bothOuterQueriesMissed = new CyclicBarrier(2);
        AtomicInteger guardedOuterQueries = new AtomicInteger();
        Answer<?> realMapperDelegate = Mockito.mockingDetails(operationMapper)
                .getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = realMapperDelegate.answer(invocation);
            int call = guardedOuterQueries.incrementAndGet();
            if (call <= 2) {
                assertThat(result).as("both real outer queries must miss").isNull();
                bothOuterQueriesMissed.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(operationMapper).selectByActorAndIdempotencyKey(memberUserId, key);

        List<HouseholdMutationResponse> responses;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HouseholdMutationResponse> first = executor.submit(
                    () -> operationService.leave(
                            memberUserId, memberMembershipId, 1L, key));
            Future<HouseholdMutationResponse> second = executor.submit(
                    () -> operationService.leave(
                            memberUserId, memberMembershipId, 1L, key));
            responses = List.of(await(first), await(second));
        }

        assertThat(guardedOuterQueries).hasValue(2);
        assertThat(responses)
                .extracting(HouseholdMutationResponse::replayed)
                .containsExactlyInAnyOrder(false, true);
        assertThat(responses)
                .extracting(HouseholdMutationResponse::operationType)
                .containsOnly(DinnerHouseholdOperationService.MEMBER_LEAVE);
        assertThat(responses)
                .extracting(HouseholdMutationResponse::actorHasHousehold)
                .containsOnly(false);
        assertThat(responses)
                .extracting(HouseholdMutationResponse::householdVersion)
                .containsOnly(2L);

        verify(operationMapper, times(2))
                .selectByActorAndIdempotencyKeyForUpdate(memberUserId, key);
        verify(memberMapper, times(1)).selectActiveByUserId(memberUserId);
        assertCommittedTermination(key);
    }

    @Test
    void operationInsertFailureRollsBackEveryEarlierAggregateMutation() {
        AtomicInteger insertAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            insertAttempts.incrementAndGet();
            assertTerminationAggregateState();
            throw new DataIntegrityViolationException("forced operation insert failure");
        }).when(operationMapper).insert(any(DinnerHouseholdOperationEntity.class));

        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> operationService.leave(
                memberUserId, memberMembershipId, 1L, key))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("forced operation insert failure");

        assertThat(insertAttempts).hasValue(1);
        assertOriginalAggregateState(key);
    }

    @Test
    void leaveThenRejoinCreatesANewHistoryWindowAndConsumesTheReplacementInvite() {
        operationService.leave(
                memberUserId, memberMembershipId, 1L, UUID.randomUUID().toString());

        var generated = writeService.refreshInvite(ownerUserId);
        var joined = writeService.join(memberUserId, generated.inviteCode());

        assertThat(joined.myMembershipId()).isNotEqualTo(memberMembershipId);
        assertThat(joined.myMembershipVersion()).isEqualTo(1L);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_members "
                        + "WHERE household_id = ? AND user_id = ? AND status = 'LEFT' "
                        + "AND history_visible_from < ended_at",
                householdId,
                memberUserId)).isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_members "
                        + "WHERE household_id = ? AND user_id = ? AND status = 'ACTIVE' "
                        + "AND history_visible_from = joined_at",
                householdId,
                memberUserId)).isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_invite_codes "
                        + "WHERE household_id = ? AND consumed_by = ? "
                        + "AND consumed_at IS NOT NULL AND open_household_id IS NULL",
                householdId,
                memberUserId)).isEqualTo(1);
    }

    @Test
    void ownershipTransferSwapsRolesAndAdvancesTheHouseholdVersion() {
        var response = operationService.transferOwnership(
                ownerUserId,
                ownerMembershipId,
                1L,
                memberMembershipId,
                1L,
                UUID.randomUUID().toString());

        assertThat(response.operationType())
                .isEqualTo(DinnerHouseholdOperationService.OWNERSHIP_TRANSFER);
        assertThat(response.householdVersion()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CONCAT(role, '|', version) FROM dinner_household_members WHERE id = ?",
                String.class,
                ownerMembershipId)).isEqualTo("MEMBER|2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CONCAT(role, '|', version) FROM dinner_household_members WHERE id = ?",
                String.class,
                memberMembershipId)).isEqualTo("OWNER|2");
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_invite_codes WHERE id = ? "
                        + "AND revoked_at IS NULL AND consumed_at IS NULL",
                inviteId)).isEqualTo(1);
    }

    @Test
    void currentMemberAccountCleanupPreservesHouseholdAndEndsMembership() {
        transactionTemplate.executeWithoutResult(ignored -> accountCleanupService.removeUser(
                memberUserId, LocalDateTime.of(2026, 7, 22, 8, 0)));

        assertThat(count("SELECT COUNT(*) FROM dinner_households WHERE id = ?", householdId))
                .isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_members WHERE id = ?",
                memberMembershipId)).isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_members "
                        + "WHERE id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
                ownerMembershipId)).isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_menu_selections WHERE user_id = ?", memberUserId))
                .isZero();
    }

    @Test
    @Timeout(30)
    void accountDeletionSeesMemberCommittedWhileWaitingForHouseholdLock() throws Exception {
        jdbcTemplate.update(
                "DELETE FROM dinner_household_members WHERE id = ?", memberMembershipId);
        String openid = "join-race-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO wechat_user_identities (user_id, openid) VALUES (?, ?)",
                ownerUserId,
                openid);
        CountDownLatch householdLocked = new CountDownLatch(1);
        CountDownLatch candidateRead = new CountDownLatch(1);
        Answer<?> realMemberMapperDelegate = Mockito.mockingDetails(memberMapper)
                .getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = realMemberMapperDelegate.answer(invocation);
            candidateRead.countDown();
            return result;
        }).when(memberMapper).selectActiveByUserId(ownerUserId);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> joining = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(ignored -> {
                    householdMapper.selectByIdForUpdate(householdId);
                    householdLocked.countDown();
                    try {
                        assertThat(candidateRead.await(10, TimeUnit.SECONDS))
                                .as("deletion established its pre-lock snapshot")
                                .isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    memberMembershipId = insertMembership(memberUserId, "MEMBER", 2);
                });
                return null;
            });
            assertThat(householdLocked.await(10, TimeUnit.SECONDS))
                    .as("joining transaction acquired the household lock")
                    .isTrue();
            Future<Void> deleting = executor.submit(() -> {
                accountDeletionTransaction.deleteVerified(ownerUserId, openid);
                return null;
            });

            joining.get(15, TimeUnit.SECONDS);
            deleting.get(15, TimeUnit.SECONDS);
        }

        assertThat(count("SELECT COUNT(*) FROM dinner_households WHERE id = ?", householdId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CONCAT(role, '|', status, '|', version) "
                        + "FROM dinner_household_members "
                        + "WHERE household_id = ? AND user_id = ?",
                String.class,
                householdId,
                memberUserId)).isEqualTo("OWNER|ACTIVE|2");
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_members "
                        + "WHERE household_id = ? AND user_id = ?",
                householdId,
                ownerUserId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CONCAT(status, '|', username) FROM users WHERE id = ?",
                String.class,
                ownerUserId)).isEqualTo("DELETED|deleted_user_" + ownerUserId);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_recipes WHERE household_id = ?", householdId))
                .isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_inventory WHERE household_id = ?",
                householdId)).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void dissolutionSeesRecipeCommittedWhileWaitingForHouseholdLock() throws Exception {
        String openid = "recipe-race-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO wechat_user_identities (user_id, openid) VALUES (?, ?)",
                ownerUserId,
                openid);
        String householdName = jdbcTemplate.queryForObject(
                "SELECT name FROM dinner_households WHERE id = ?", String.class, householdId);
        String key = UUID.randomUUID().toString();
        String fingerprint = fingerprinter.fingerprint(
                DinnerHouseholdDissolutionService.HOUSEHOLD_DISSOLUTION,
                ownerMembershipId,
                1L,
                null,
                null,
                householdName);
        var command = new DinnerHouseholdOperationService.HouseholdOperationCommand(
                ownerUserId,
                ownerMembershipId,
                1L,
                null,
                null,
                DinnerHouseholdDissolutionService.HOUSEHOLD_DISSOLUTION,
                key,
                fingerprint);
        CountDownLatch householdLocked = new CountDownLatch(1);
        CountDownLatch candidateRead = new CountDownLatch(1);
        Answer<?> realMemberMapperDelegate = Mockito.mockingDetails(memberMapper)
                .getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = realMemberMapperDelegate.answer(invocation);
            candidateRead.countDown();
            return result;
        }).when(memberMapper).selectActiveByUserId(ownerUserId);

        Long newRecipeId;
        HouseholdMutationResponse response;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> creatingRecipe = executor.submit(() ->
                    transactionTemplate.execute(ignored -> {
                        householdMapper.selectByIdForUpdate(householdId);
                        householdLocked.countDown();
                        try {
                            assertThat(candidateRead.await(10, TimeUnit.SECONDS))
                                    .as("dissolution established its pre-lock snapshot")
                                    .isTrue();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        return insertRacingMemberDraft(
                                "recipe_race_" + suffix.substring(0, 8));
                    }));
            assertThat(householdLocked.await(10, TimeUnit.SECONDS))
                    .as("recipe transaction acquired the household lock")
                    .isTrue();
            Future<HouseholdMutationResponse> dissolving = executor.submit(
                    () -> dissolutionTransaction.dissolve(command, householdName, openid));

            newRecipeId = creatingRecipe.get(15, TimeUnit.SECONDS);
            response = dissolving.get(15, TimeUnit.SECONDS);
        }

        assertThat(response.actorHasHousehold()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM dinner_households WHERE id = ?", householdId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CONCAT(COALESCE(household_id, 'NULL'), '|', status, '|', version) "
                        + "FROM dinner_recipes WHERE id = ?",
                String.class,
                newRecipeId)).isEqualTo("NULL|DRAFT|2");
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                ownerUserId,
                key)).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void crossedMembershipHistoriesDoNotDeadlockConcurrentAccountDeletion() throws Exception {
        CrossHistoryDeletionFixture fixture = insertCrossHistoryDeletionFixture();
        CyclicBarrier bothCurrentHouseholdsLocked = new CyclicBarrier(2);
        Answer<?> realHouseholdMapperDelegate = Mockito.mockingDetails(householdMapper)
                .getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = realHouseholdMapperDelegate.answer(invocation);
            Long lockedHouseholdId = invocation.getArgument(0);
            if (List.of(fixture.firstHouseholdId(), fixture.secondHouseholdId())
                    .contains(lockedHouseholdId)) {
                bothCurrentHouseholdsLocked.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(householdMapper).selectByIdForUpdate(any());

        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> first = executor.submit(() -> {
                accountDeletionTransaction.deleteVerified(
                        fixture.firstActorUserId(), fixture.firstOpenid());
                return null;
            });
            Future<Void> second = executor.submit(() -> {
                accountDeletionTransaction.deleteVerified(
                        fixture.secondActorUserId(), fixture.secondOpenid());
                return null;
            });
            collectFailure(first, failures);
            collectFailure(second, failures);
            verify(memberMapper, times(2)).selectByIdsForUpdate(any());
            assertThat(failures)
                    .as("both crossed-history account deletions must commit without a deadlock")
                    .isEmpty();
            assertThat(count(
                    "SELECT COUNT(*) FROM dinner_households WHERE id IN (?, ?)",
                    fixture.firstHouseholdId(),
                    fixture.secondHouseholdId())).isEqualTo(2);
            assertThat(count(
                    "SELECT COUNT(*) FROM dinner_household_members "
                            + "WHERE household_id IN (?, ?) AND user_id IN (?, ?) "
                            + "AND role = 'OWNER' AND status = 'ACTIVE'",
                    fixture.firstHouseholdId(),
                    fixture.secondHouseholdId(),
                    fixture.firstSurvivorUserId(),
                    fixture.secondSurvivorUserId())).isEqualTo(2);
            assertThat(count(
                    "SELECT COUNT(*) FROM dinner_household_members WHERE user_id IN (?, ?)",
                    fixture.firstActorUserId(),
                    fixture.secondActorUserId())).isZero();
            assertThat(count(
                    "SELECT COUNT(*) FROM wechat_user_identities WHERE user_id IN (?, ?)",
                    fixture.firstActorUserId(),
                    fixture.secondActorUserId())).isZero();
            assertThat(count(
                    "SELECT COUNT(*) FROM users WHERE id IN (?, ?) AND status = 'DELETED'",
                    fixture.firstActorUserId(),
                    fixture.secondActorUserId())).isEqualTo(2);
        } finally {
            reset(householdMapper, memberMapper);
            deleteCrossHistoryDeletionFixture(fixture);
        }
    }

    @Test
    void dissolutionPurgesTheAggregateButRetainsTheIdempotentResult() {
        String openid = "task9_" + suffix;
        jdbcTemplate.update(
                "INSERT INTO wechat_user_identities (user_id, openid) VALUES (?, ?)",
                ownerUserId,
                openid);
        String key = UUID.randomUUID().toString();
        String householdName = jdbcTemplate.queryForObject(
                "SELECT name FROM dinner_households WHERE id = ?", String.class, householdId);
        String fingerprint = fingerprinter.fingerprint(
                DinnerHouseholdDissolutionService.HOUSEHOLD_DISSOLUTION,
                ownerMembershipId,
                1L,
                null,
                null,
                householdName);
        var command = new DinnerHouseholdOperationService.HouseholdOperationCommand(
                ownerUserId,
                ownerMembershipId,
                1L,
                null,
                null,
                DinnerHouseholdDissolutionService.HOUSEHOLD_DISSOLUTION,
                key,
                fingerprint);

        var response = dissolutionTransaction.dissolve(command, householdName, openid);

        assertThat(response.actorHasHousehold()).isFalse();
        assertThat(response.householdVersion()).isNull();
        assertThat(count("SELECT COUNT(*) FROM dinner_households WHERE id = ?", householdId))
                .isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                ownerUserId,
                key)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("householdLockCompetitions")
    @Timeout(20)
    void householdLifecycleCompetitionsSerializeOnTheAggregateLock(
            String competition,
            String firstMutation,
            String secondMutation
    ) throws Exception {
        CountDownLatch firstHasHouseholdLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondIsAttemptingLock = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> first = executor.submit(() -> {
                runAggregateTransaction(
                        firstMutation,
                        firstHasHouseholdLock,
                        releaseFirst,
                        null);
                return null;
            });
            assertThat(firstHasHouseholdLock.await(5, TimeUnit.SECONDS))
                    .as(competition + " first operation acquired household lock")
                    .isTrue();

            Future<Void> second = executor.submit(() -> {
                runAggregateTransaction(
                        secondMutation,
                        null,
                        null,
                        secondIsAttemptingLock);
                return null;
            });
            assertThat(secondIsAttemptingLock.await(5, TimeUnit.SECONDS))
                    .as(competition + " second operation reached household lock")
                    .isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .as(competition + " must block instead of observing a partial aggregate")
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void unrelatedIntegrityViolationRemainsAFullInternalRollback() throws Exception {
        String attemptedName = "rolled_back_household_" + suffix;
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var insertHousehold = connection.prepareStatement(
                        "INSERT INTO dinner_households (name, created_by) VALUES (?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    insertHousehold.setString(1, attemptedName);
                    insertHousehold.setLong(2, ownerUserId);
                    insertHousehold.executeUpdate();
                }
                try (var duplicateActiveUser = connection.prepareStatement(
                        "INSERT INTO dinner_household_members "
                                + "(household_id, user_id, joined_at, role, status, seat_no, "
                                + "history_visible_from) "
                                + "VALUES ((SELECT id FROM dinner_households WHERE name = ?), "
                                + "?, UTC_TIMESTAMP(3), 'MEMBER', 'ACTIVE', 1, UTC_TIMESTAMP(3))")) {
                    duplicateActiveUser.setString(1, attemptedName);
                    duplicateActiveUser.setLong(2, memberUserId);
                    assertThatThrownBy(duplicateActiveUser::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .extracting(throwable -> ((SQLException) throwable).getSQLState())
                            .isEqualTo("23000");
                }
            } finally {
                connection.rollback();
            }
        }
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_households WHERE name = ?", attemptedName))
                .isZero();
    }

    private static java.util.stream.Stream<Arguments> householdLockCompetitions() {
        return java.util.stream.Stream.of(
                Arguments.of(
                        "exit x menu update",
                        "UPDATE dinner_household_members SET version = version + 1 WHERE id = ?",
                        "UPDATE dinner_menus SET version = version + 1 WHERE id = ?"),
                Arguments.of(
                        "remove x complete",
                        "UPDATE dinner_household_members SET version = version + 1 WHERE id = ?",
                        "UPDATE dinner_menus SET version = version + 1 WHERE id = ?"),
                Arguments.of(
                        "transfer x invite refresh",
                        "UPDATE dinner_household_members SET version = version + 1 WHERE id = ?",
                        "UPDATE dinner_invite_codes SET expires_at = DATE_ADD(expires_at, INTERVAL 1 SECOND) WHERE id = ?"),
                Arguments.of(
                        "deletion x publish",
                        "UPDATE dinner_household_members SET version = version + 1 WHERE id = ?",
                        "UPDATE dinner_recipes SET version = version + 1 WHERE id = ?"),
                Arguments.of(
                        "dissolution x inventory update",
                        "UPDATE dinner_households SET version = version + 1 WHERE id = ?",
                        "UPDATE dinner_household_inventory SET version = version + 1 WHERE id = ?"));
    }

    private void runAggregateTransaction(
            String mutation,
            CountDownLatch locked,
            CountDownLatch release,
            CountDownLatch attempting
    ) throws Exception {
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (attempting != null) {
                    attempting.countDown();
                }
                try (var householdLock = connection.prepareStatement(
                        "SELECT id FROM dinner_households WHERE id = ? FOR UPDATE")) {
                    householdLock.setLong(1, householdId);
                    try (var rows = householdLock.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
                if (locked != null) {
                    locked.countDown();
                }
                if (release != null) {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                }
                try (var update = connection.prepareStatement(mutation)) {
                    long targetId = mutation.contains("dinner_menus") ? menuId
                            : mutation.contains("dinner_invite_codes") ? inviteId
                            : mutation.contains("dinner_recipes") ? draftRecipeId
                            : mutation.contains("dinner_household_inventory") ? inventoryId
                            : mutation.contains("dinner_households") ? householdId
                            : memberMembershipId;
                    update.setLong(1, targetId);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void assertCommittedTermination(String key) {
        assertTerminationAggregateState();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                memberUserId,
                key)).isEqualTo(1);
        LocalDateTime createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                LocalDateTime.class,
                memberUserId,
                key);
        LocalDateTime expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                LocalDateTime.class,
                memberUserId,
                key);
        assertThat(expiresAt).isEqualTo(createdAt.plusDays(14));
    }

    private void assertTerminationAggregateState() {
        Map<String, Object> member = jdbcTemplate.queryForMap(
                "SELECT status, version, ended_by, end_reason, ended_at "
                        + "FROM dinner_household_members WHERE id = ?",
                memberMembershipId);
        assertThat(member.get("status")).isEqualTo("LEFT");
        assertThat(((Number) member.get("version")).longValue()).isEqualTo(2L);
        assertThat(((Number) member.get("ended_by")).longValue()).isEqualTo(memberUserId);
        assertThat(member.get("end_reason")).isEqualTo("SELF_LEFT");
        assertThat(member.get("ended_at")).isNotNull();

        Map<String, Object> household = jdbcTemplate.queryForMap(
                "SELECT version, invite_revision FROM dinner_households WHERE id = ?",
                householdId);
        assertThat(((Number) household.get("version")).longValue()).isEqualTo(2L);
        assertThat(((Number) household.get("invite_revision")).longValue()).isEqualTo(1L);

        Map<String, Object> invite = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revocation_reason FROM dinner_invite_codes WHERE id = ?",
                inviteId);
        assertThat(invite.get("revoked_at")).isNotNull();
        assertThat(invite.get("revocation_reason")).isEqualTo("MEMBERSHIP_CHANGED");

        Map<String, Object> menu = jdbcTemplate.queryForMap(
                "SELECT status, version, confirmed_by, confirmed_at "
                        + "FROM dinner_menus WHERE id = ?",
                menuId);
        assertThat(menu.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) menu.get("version")).longValue()).isEqualTo(5L);
        assertThat(menu.get("confirmed_by")).isNull();
        assertThat(menu.get("confirmed_at")).isNull();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_menu_selections WHERE menu_id = ?",
                menuId)).isZero();

        Map<String, Object> draft = jdbcTemplate.queryForMap(
                "SELECT household_id, version, source_recipe_id, revision_of_recipe_id, "
                        + "base_published_version FROM dinner_recipes WHERE id = ?",
                draftRecipeId);
        assertThat(draft.get("household_id")).isNull();
        assertThat(((Number) draft.get("version")).longValue()).isEqualTo(4L);
        assertThat(((Number) draft.get("source_recipe_id")).longValue())
                .isEqualTo(systemSourceRecipeId);
        assertThat(draft.get("revision_of_recipe_id")).isNull();
        assertThat(draft.get("base_published_version")).isNull();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_recipe_ingredients WHERE recipe_id = ?",
                draftRecipeId)).isZero();

        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_inventory WHERE id = ?",
                inventoryId)).isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_ingredients WHERE id = ?",
                householdIngredientId)).isEqualTo(1);
    }

    private void assertOriginalAggregateState(String key) {
        Map<String, Object> member = jdbcTemplate.queryForMap(
                "SELECT status, version, ended_by, end_reason, ended_at "
                        + "FROM dinner_household_members WHERE id = ?",
                memberMembershipId);
        assertThat(member.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) member.get("version")).longValue()).isEqualTo(1L);
        assertThat(member.get("ended_by")).isNull();
        assertThat(member.get("end_reason")).isNull();
        assertThat(member.get("ended_at")).isNull();

        Map<String, Object> household = jdbcTemplate.queryForMap(
                "SELECT version, invite_revision FROM dinner_households WHERE id = ?",
                householdId);
        assertThat(((Number) household.get("version")).longValue()).isEqualTo(1L);
        assertThat(((Number) household.get("invite_revision")).longValue()).isZero();

        Map<String, Object> invite = jdbcTemplate.queryForMap(
                "SELECT revoked_at, revocation_reason FROM dinner_invite_codes WHERE id = ?",
                inviteId);
        assertThat(invite.get("revoked_at")).isNull();
        assertThat(invite.get("revocation_reason")).isNull();

        Map<String, Object> menu = jdbcTemplate.queryForMap(
                "SELECT status, version, confirmed_by, confirmed_at "
                        + "FROM dinner_menus WHERE id = ?",
                menuId);
        assertThat(menu.get("status")).isEqualTo("CONFIRMED");
        assertThat(((Number) menu.get("version")).longValue()).isEqualTo(4L);
        assertThat(((Number) menu.get("confirmed_by")).longValue()).isEqualTo(ownerUserId);
        assertThat(menu.get("confirmed_at")).isNotNull();
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_menu_selections WHERE menu_id = ?",
                menuId)).isEqualTo(1);

        Map<String, Object> draft = jdbcTemplate.queryForMap(
                "SELECT household_id, version, source_recipe_id "
                        + "FROM dinner_recipes WHERE id = ?",
                draftRecipeId);
        assertThat(((Number) draft.get("household_id")).longValue()).isEqualTo(householdId);
        assertThat(((Number) draft.get("version")).longValue()).isEqualTo(3L);
        assertThat(((Number) draft.get("source_recipe_id")).longValue())
                .isEqualTo(systemSourceRecipeId);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_recipe_ingredients WHERE recipe_id = ?",
                draftRecipeId)).isEqualTo(1);

        Map<String, Object> inventory = jdbcTemplate.queryForMap(
                "SELECT quantity, version FROM dinner_household_inventory WHERE id = ?",
                inventoryId);
        assertThat(new BigDecimal(inventory.get("quantity").toString()))
                .isEqualByComparingTo("2.000");
        assertThat(((Number) inventory.get("version")).longValue()).isEqualTo(1L);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_ingredients WHERE id = ?",
                householdIngredientId)).isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM dinner_household_operations "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                memberUserId,
                key)).isZero();
    }

    private void requireDedicatedV8Database() {
        requireDedicatedCatalogAtRuntime();
        assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class))
                .as("membership termination acceptance must run on MySQL 8")
                .startsWith("8.");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version FROM flyway_schema_history "
                                + "WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1",
                        String.class))
                .as("membership termination acceptance requires Flyway V8")
                .isEqualTo("8");
    }

    private void requireDedicatedCatalogAtRuntime() {
        String expected = System.getenv("OSHEEEP_DB_TEST_NAME");
        assertThat(expected).as("OSHEEEP_DB_TEST_NAME safety gate").isNotBlank();
        assertThat(System.getenv("OSHEEEP_DB_NAME"))
                .as("raw selected database must be the dedicated test database")
                .isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .as("active catalog must be the dedicated test database")
                .isEqualTo(expected);
    }

    private Long insertUser(String username) {
        jdbcTemplate.update(
                "INSERT INTO users (username, status) VALUES (?, 'ACTIVE')", username);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private Long insertHousehold() {
        String name = "termination_household_" + suffix;
        jdbcTemplate.update(
                "INSERT INTO dinner_households (name, created_by) VALUES (?, ?)",
                name,
                ownerUserId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_households WHERE name = ? AND created_by = ?",
                Long.class,
                name,
                ownerUserId);
    }

    private Long insertMembership(Long userId, String role, int seatNo) {
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(household_id, user_id, role, status, seat_no, "
                        + "history_visible_from, version) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(3), 1)",
                householdId,
                userId,
                role,
                seatNo);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_household_members "
                        + "WHERE household_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Long.class,
                householdId,
                userId);
    }

    private CrossHistoryDeletionFixture insertCrossHistoryDeletionFixture() {
        Long firstActorUserId = insertUser("cross_history_actor_u_" + suffix);
        Long secondActorUserId = insertUser("cross_history_actor_v_" + suffix);
        Long firstSurvivorUserId = insertUser("cross_history_survivor_h1_" + suffix);
        Long secondSurvivorUserId = insertUser("cross_history_survivor_h2_" + suffix);
        String firstOpenid = "cross-u-" + suffix;
        String secondOpenid = "cross-v-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO wechat_user_identities (user_id, openid) VALUES (?, ?), (?, ?)",
                firstActorUserId,
                firstOpenid,
                secondActorUserId,
                secondOpenid);

        Long firstHouseholdId = insertHousehold(
                "cross_history_h1_" + suffix, firstSurvivorUserId);
        insertActiveMembership(firstHouseholdId, firstSurvivorUserId, "OWNER", 1);
        insertActiveMembership(firstHouseholdId, firstActorUserId, "MEMBER", 2);
        insertHistoricalMembership(
                firstHouseholdId, secondActorUserId, firstSurvivorUserId);

        Long secondHouseholdId = insertHousehold(
                "cross_history_h2_" + suffix, secondSurvivorUserId);
        insertActiveMembership(secondHouseholdId, secondSurvivorUserId, "OWNER", 1);
        insertActiveMembership(secondHouseholdId, secondActorUserId, "MEMBER", 2);
        insertHistoricalMembership(
                secondHouseholdId, firstActorUserId, secondSurvivorUserId);

        return new CrossHistoryDeletionFixture(
                firstActorUserId,
                secondActorUserId,
                firstSurvivorUserId,
                secondSurvivorUserId,
                firstHouseholdId,
                secondHouseholdId,
                firstOpenid,
                secondOpenid);
    }

    private Long insertHousehold(String name, Long createdBy) {
        jdbcTemplate.update(
                "INSERT INTO dinner_households (name, created_by) VALUES (?, ?)",
                name,
                createdBy);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_households WHERE name = ? AND created_by = ?",
                Long.class,
                name,
                createdBy);
    }

    private void insertActiveMembership(
            Long targetHouseholdId,
            Long userId,
            String role,
            int seatNo
    ) {
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(household_id, user_id, role, status, seat_no, "
                        + "history_visible_from, version) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(3), 1)",
                targetHouseholdId,
                userId,
                role,
                seatNo);
    }

    private void insertHistoricalMembership(
            Long targetHouseholdId,
            Long userId,
            Long endedBy
    ) {
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members "
                        + "(household_id, user_id, role, status, seat_no, joined_at, "
                        + "history_visible_from, version, ended_at, ended_by, end_reason) "
                        + "VALUES (?, ?, 'MEMBER', 'LEFT', 2, "
                        + "UTC_TIMESTAMP(3) - INTERVAL 2 DAY, "
                        + "UTC_TIMESTAMP(3) - INTERVAL 2 DAY, 2, "
                        + "UTC_TIMESTAMP(3) - INTERVAL 1 DAY, ?, 'SELF_LEFT')",
                targetHouseholdId,
                userId,
                endedBy);
    }

    private void deleteCrossHistoryDeletionFixture(CrossHistoryDeletionFixture fixture) {
        requireDedicatedCatalogAtRuntime();
        jdbcTemplate.update(
                "DELETE FROM dinner_household_operations "
                        + "WHERE household_id IN (?, ?) OR actor_id IN (?, ?)",
                fixture.firstHouseholdId(),
                fixture.secondHouseholdId(),
                fixture.firstActorUserId(),
                fixture.secondActorUserId());
        jdbcTemplate.update(
                "DELETE FROM dinner_household_members WHERE household_id IN (?, ?)",
                fixture.firstHouseholdId(),
                fixture.secondHouseholdId());
        jdbcTemplate.update(
                "DELETE FROM dinner_households WHERE id IN (?, ?)",
                fixture.firstHouseholdId(),
                fixture.secondHouseholdId());
        jdbcTemplate.update(
                "DELETE FROM wechat_user_identities WHERE user_id IN (?, ?, ?, ?)",
                fixture.firstActorUserId(),
                fixture.secondActorUserId(),
                fixture.firstSurvivorUserId(),
                fixture.secondSurvivorUserId());
        jdbcTemplate.update(
                "DELETE FROM users WHERE id IN (?, ?, ?, ?)",
                fixture.firstActorUserId(),
                fixture.secondActorUserId(),
                fixture.firstSurvivorUserId(),
                fixture.secondSurvivorUserId());
    }

    private Long insertOpenInvite() {
        String hash = suffix + suffix;
        jdbcTemplate.update(
                "INSERT INTO dinner_invite_codes "
                        + "(household_id, code_hash, expires_at, created_by) "
                        + "VALUES (?, ?, UTC_TIMESTAMP(3) + INTERVAL 1 DAY, ?)",
                householdId,
                hash,
                ownerUserId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_invite_codes WHERE code_hash = ?", Long.class, hash);
    }

    private Long insertHouseholdIngredient() {
        String name = "termination_ingredient_" + suffix.substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO dinner_ingredients "
                        + "(scope, household_id, name, category, default_unit, status) "
                        + "VALUES ('HOUSEHOLD', ?, ?, 'TEST', '个', 'ACTIVE')",
                householdId,
                name);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_ingredients WHERE household_id = ? AND name = ?",
                Long.class,
                householdId,
                name);
    }

    private Long insertMemberDraft() {
        String name = "termination_draft_" + suffix.substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO dinner_recipes "
                        + "(scope, household_id, name, category, flavor, servings, "
                        + "estimated_minutes, creator_id, last_modified_by, source_recipe_id, "
                        + "status, version) "
                        + "VALUES ('HOUSEHOLD', ?, ?, 'TEST', 'TEST', 2, 10, ?, ?, ?, "
                        + "'DRAFT', 3)",
                householdId,
                name,
                memberUserId,
                memberUserId,
                systemSourceRecipeId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_recipes WHERE household_id = ? AND name = ?",
                Long.class,
                householdId,
                name);
    }

    private Long insertRacingMemberDraft(String name) {
        jdbcTemplate.update(
                "INSERT INTO dinner_recipes "
                        + "(scope, household_id, name, category, flavor, servings, "
                        + "estimated_minutes, creator_id, last_modified_by, source_recipe_id, "
                        + "status, version) "
                        + "VALUES ('HOUSEHOLD', ?, ?, 'TEST', 'TEST', 2, 10, ?, ?, ?, "
                        + "'DRAFT', 1)",
                householdId,
                name,
                memberUserId,
                memberUserId,
                systemSourceRecipeId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_recipes WHERE household_id = ? AND name = ?",
                Long.class,
                householdId,
                name);
    }

    private void insertDraftIngredient() {
        jdbcTemplate.update(
                "INSERT INTO dinner_recipe_ingredients "
                        + "(recipe_id, ingredient_id, quantity, unit, is_required, sort_order) "
                        + "VALUES (?, ?, 1.000, '个', 1, 1)",
                draftRecipeId,
                householdIngredientId);
    }

    private Long insertInventory() {
        jdbcTemplate.update(
                "INSERT INTO dinner_household_inventory "
                        + "(household_id, ingredient_id, quantity, unit, version, updated_by) "
                        + "VALUES (?, ?, 2.000, '个', 1, ?)",
                householdId,
                householdIngredientId,
                ownerUserId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_household_inventory "
                        + "WHERE household_id = ? AND ingredient_id = ?",
                Long.class,
                householdId,
                householdIngredientId);
    }

    private Long insertConfirmedMenu() {
        jdbcTemplate.update(
                "INSERT INTO dinner_menus "
                        + "(household_id, menu_date, status, version, confirmed_by, confirmed_at) "
                        + "VALUES (?, CURRENT_DATE + INTERVAL 30 DAY, 'CONFIRMED', 4, ?, "
                        + "UTC_TIMESTAMP(3))",
                householdId,
                ownerUserId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_menus WHERE household_id = ?",
                Long.class,
                householdId);
    }

    private void insertMemberSelection() {
        jdbcTemplate.update(
                "INSERT INTO dinner_menu_selections "
                        + "(menu_id, user_id, recipe_id, recipe_version) VALUES (?, ?, ?, 3)",
                menuId,
                memberUserId,
                draftRecipeId);
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private HouseholdMutationResponse await(Future<HouseholdMutationResponse> future)
            throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private void collectFailure(Future<Void> future, List<Throwable> failures)
            throws InterruptedException {
        try {
            future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable failure = exception.getCause();
            while (failure.getCause() != null && failure.getCause() != failure) {
                failure = failure.getCause();
            }
            failures.add(failure);
        } catch (TimeoutException exception) {
            failures.add(exception);
        }
    }

    private record CrossHistoryDeletionFixture(
            Long firstActorUserId,
            Long secondActorUserId,
            Long firstSurvivorUserId,
            Long secondSurvivorUserId,
            Long firstHouseholdId,
            Long secondHouseholdId,
            String firstOpenid,
            String secondOpenid
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlywaySafetyConfiguration {

        @Bean
        FlywayMigrationStrategy membershipTerminationFlywayMigrationStrategy() {
            return new DinnerCustomRecipeFlywayMigrationStrategy(
                    System.getenv("OSHEEEP_DB_TEST_NAME"));
        }
    }
}
