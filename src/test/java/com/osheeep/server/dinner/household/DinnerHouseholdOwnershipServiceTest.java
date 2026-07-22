package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdOperationService.HouseholdOperationCommand;
import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdOwnershipServiceTest {

    private static final Long HOUSEHOLD_ID = 11L;
    private static final Long OWNER_USER_ID = 7L;
    private static final Long MEMBER_USER_ID = 8L;
    private static final Long OWNER_MEMBERSHIP_ID = 31L;
    private static final Long MEMBER_MEMBERSHIP_ID = 32L;
    private static final Long HOUSEHOLD_VERSION = 8L;
    private static final Long OWNER_MEMBERSHIP_VERSION = 4L;
    private static final Long MEMBER_MEMBERSHIP_VERSION = 3L;
    private static final String IDEMPOTENCY_KEY =
            "7b20fb9b-a868-48bf-98e5-36643b9921b1";
    private static final String FINGERPRINT = "ownership-transfer-fingerprint";
    private static final Instant CLOCK_INSTANT =
            Instant.parse("2026-07-22T05:00:00.123456789Z");
    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 22, 5, 0, 0, 123_000_000);
    private static final LocalDateTime JOINED_AT =
            LocalDateTime.of(2026, 7, 1, 3, 0);

    @Mock private UserMapper userMapper;
    @Mock private DinnerHouseholdOperationMapper operationMapper;
    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdOperationRetentionService retentionService;
    @Mock private DinnerMembershipTerminationService terminationService;

    private DinnerHouseholdOwnershipService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdOwnershipService(
                userMapper,
                operationMapper,
                householdMapper,
                memberMapper,
                new ObjectMapper(),
                Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void ownerTransfersAtomicallyWithoutChangingUnrelatedHouseholdState() {
        stubLockedContext(OWNER_USER_ID, ownerMembership(), List.of(
                ownerMembership(), memberMembership()));
        when(memberMapper.demoteActiveOwner(
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_VERSION)).thenReturn(1);
        when(memberMapper.promoteActiveMember(
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_VERSION)).thenReturn(1);
        when(householdMapper.advanceOwnership(
                HOUSEHOLD_ID, HOUSEHOLD_VERSION, NOW)).thenReturn(1);
        when(operationMapper.insert(any(DinnerHouseholdOperationEntity.class)))
                .thenReturn(1);

        var result = service.transfer(transferCommand());

        assertThat(result.operationType()).isEqualTo("OWNERSHIP_TRANSFER");
        assertThat(result.replayed()).isFalse();
        assertThat(result.actorHasHousehold()).isTrue();
        assertThat(result.householdVersion()).isEqualTo(9L);

        InOrder order = inOrder(userMapper, operationMapper, householdMapper, memberMapper);
        order.verify(userMapper).selectByIdForUpdate(OWNER_USER_ID);
        order.verify(operationMapper).selectByActorAndIdempotencyKeyForUpdate(
                OWNER_USER_ID, IDEMPOTENCY_KEY);
        order.verify(memberMapper).selectActiveByUserId(OWNER_USER_ID);
        order.verify(householdMapper).selectByIdForUpdate(HOUSEHOLD_ID);
        order.verify(memberMapper).selectActiveByHouseholdIdForUpdate(HOUSEHOLD_ID);
        order.verify(memberMapper).demoteActiveOwner(
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_VERSION);
        order.verify(memberMapper).promoteActiveMember(
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_VERSION);
        order.verify(householdMapper).advanceOwnership(HOUSEHOLD_ID, HOUSEHOLD_VERSION, NOW);
        order.verify(operationMapper).insert(any(DinnerHouseholdOperationEntity.class));

        ArgumentCaptor<DinnerHouseholdOperationEntity> operationCaptor =
                ArgumentCaptor.forClass(DinnerHouseholdOperationEntity.class);
        verify(operationMapper).insert(operationCaptor.capture());
        DinnerHouseholdOperationEntity operation = operationCaptor.getValue();
        assertThat(operation.getHouseholdId()).isEqualTo(HOUSEHOLD_ID);
        assertThat(operation.getActorId()).isEqualTo(OWNER_USER_ID);
        assertThat(operation.getActorMembershipId()).isEqualTo(OWNER_MEMBERSHIP_ID);
        assertThat(operation.getTargetMemberId()).isEqualTo(MEMBER_MEMBERSHIP_ID);
        assertThat(operation.getOperationType()).isEqualTo("OWNERSHIP_TRANSFER");
        assertThat(operation.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(operation.getRequestFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(operation.getResultSchemaVersion()).isEqualTo(1);
        assertThat(operation.getResultHouseholdVersion()).isEqualTo(9L);
        assertThat(operation.getResultPayload()).isEqualTo("{\"actorHasHousehold\":true}");
        assertThat(operation.getCreatedAt()).isEqualTo(NOW);
        assertThat(operation.getExpiresAt()).isEqualTo(NOW.plusDays(14));
    }

    @Test
    void memberCannotTransferOwnership() {
        stubLockedContext(MEMBER_USER_ID, memberMembership(), List.of(
                ownerMembership(), memberMembership()));

        assertBusinessError(
                () -> service.transfer(new HouseholdOperationCommand(
                        MEMBER_USER_ID,
                        MEMBER_MEMBERSHIP_ID,
                        HOUSEHOLD_VERSION,
                        OWNER_MEMBERSHIP_ID,
                        OWNER_MEMBERSHIP_VERSION,
                        DinnerHouseholdOperationService.OWNERSHIP_TRANSFER,
                        IDEMPOTENCY_KEY,
                        FINGERPRINT)),
                ErrorCode.DINNER_HOUSEHOLD_OWNER_REQUIRED);

        assertNoRoleMutations();
    }

    @Test
    void singleMemberHouseholdHasNoTransferTarget() {
        stubLockedContext(OWNER_USER_ID, ownerMembership(), List.of(ownerMembership()));

        assertBusinessError(
                () -> service.transfer(transferCommand()),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_NOT_FOUND);

        assertNoRoleMutations();
    }

    @Test
    void ownerCannotTransferToSelf() {
        stubLockedContext(OWNER_USER_ID, ownerMembership(), List.of(
                ownerMembership(), memberMembership()));

        HouseholdOperationCommand command = new HouseholdOperationCommand(
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                OWNER_MEMBERSHIP_ID,
                OWNER_MEMBERSHIP_VERSION,
                DinnerHouseholdOperationService.OWNERSHIP_TRANSFER,
                IDEMPOTENCY_KEY,
                FINGERPRINT);
        assertBusinessError(
                () -> service.transfer(command),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);

        assertNoRoleMutations();
    }

    @Test
    void foreignOrInactiveTargetIsNotDisclosed() {
        stubLockedContext(OWNER_USER_ID, ownerMembership(), List.of(
                ownerMembership(), memberMembership()));

        HouseholdOperationCommand command = new HouseholdOperationCommand(
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                999L,
                1L,
                DinnerHouseholdOperationService.OWNERSHIP_TRANSFER,
                IDEMPOTENCY_KEY,
                FINGERPRINT);
        assertBusinessError(
                () -> service.transfer(command),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_NOT_FOUND);

        assertNoRoleMutations();
    }

    @Test
    void staleHouseholdVersionIsRejected() {
        stubLockedContext(OWNER_USER_ID, ownerMembership(), List.of(
                ownerMembership(), memberMembership()));

        HouseholdOperationCommand command = new HouseholdOperationCommand(
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION - 1,
                MEMBER_MEMBERSHIP_ID,
                MEMBER_MEMBERSHIP_VERSION,
                DinnerHouseholdOperationService.OWNERSHIP_TRANSFER,
                IDEMPOTENCY_KEY,
                FINGERPRINT);
        assertBusinessError(
                () -> service.transfer(command),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);

        assertNoRoleMutations();
    }

    @Test
    void staleTargetVersionIsRejected() {
        stubLockedContext(OWNER_USER_ID, ownerMembership(), List.of(
                ownerMembership(), memberMembership()));

        HouseholdOperationCommand command = new HouseholdOperationCommand(
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                MEMBER_MEMBERSHIP_ID,
                MEMBER_MEMBERSHIP_VERSION - 1,
                DinnerHouseholdOperationService.OWNERSHIP_TRANSFER,
                IDEMPOTENCY_KEY,
                FINGERPRINT);
        assertBusinessError(
                () -> service.transfer(command),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);

        assertNoRoleMutations();
    }

    @Test
    void changedActorSnapshotIsAHouseholdConflict() {
        DinnerHouseholdMemberEntity candidate = ownerMembership();
        DinnerHouseholdMemberEntity lockedOwner = ownerMembership();
        lockedOwner.setVersion(OWNER_MEMBERSHIP_VERSION + 1);
        stubLockedContext(OWNER_USER_ID, candidate, List.of(
                lockedOwner, memberMembership()));

        assertBusinessError(
                () -> service.transfer(transferCommand()),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);

        assertNoRoleMutations();
    }

    @Test
    void mismatchedActorMembershipContextIsRejected() {
        stubLockedContext(OWNER_USER_ID, ownerMembership(), List.of(
                ownerMembership(), memberMembership()));

        HouseholdOperationCommand command = new HouseholdOperationCommand(
                OWNER_USER_ID,
                999L,
                HOUSEHOLD_VERSION,
                MEMBER_MEMBERSHIP_ID,
                MEMBER_MEMBERSHIP_VERSION,
                DinnerHouseholdOperationService.OWNERSHIP_TRANSFER,
                IDEMPOTENCY_KEY,
                FINGERPRINT);
        assertBusinessError(
                () -> service.transfer(command),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);

        assertNoRoleMutations();
    }

    @Test
    void storedSuccessReplaysAfterActorIsNoLongerOwner() {
        DinnerHouseholdOperationEntity existing = operationResult();
        when(userMapper.selectByIdForUpdate(OWNER_USER_ID)).thenReturn(activeUser(OWNER_USER_ID));
        when(operationMapper.selectByActorAndIdempotencyKeyForUpdate(
                OWNER_USER_ID, IDEMPOTENCY_KEY)).thenReturn(existing);

        var result = service.transfer(transferCommand());

        assertThat(result.operationType()).isEqualTo("OWNERSHIP_TRANSFER");
        assertThat(result.replayed()).isTrue();
        assertThat(result.actorHasHousehold()).isTrue();
        assertThat(result.householdVersion()).isEqualTo(9L);
        verifyNoInteractions(householdMapper, memberMapper);
        verify(operationMapper, never()).insert(any(DinnerHouseholdOperationEntity.class));
    }

    @Test
    void concurrentSameKeyTransfersBothPrecheckEmptyThenLoserReplaysAfterActorLock()
            throws Exception {
        HouseholdOperationFingerprinter fingerprinter =
                new HouseholdOperationFingerprinter("test-secret-at-least-32-characters");
        DinnerHouseholdOperationService orchestrator = new DinnerHouseholdOperationService(
                operationMapper,
                fingerprinter,
                retentionService,
                terminationService,
                service,
                new ObjectMapper(),
                Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC));
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        DinnerHouseholdEntity household = household();
        AtomicReference<DinnerHouseholdOperationEntity> storedOperation =
                new AtomicReference<>();
        AtomicInteger outerLookupCount = new AtomicInteger();
        CountDownLatch bothOuterPrechecks = new CountDownLatch(2);
        ReentrantLock actorRowLock = new ReentrantLock(true);

        when(operationMapper.selectByActorAndIdempotencyKey(
                OWNER_USER_ID, IDEMPOTENCY_KEY)).thenAnswer(invocation -> {
                    if (outerLookupCount.incrementAndGet() <= 2) {
                        bothOuterPrechecks.countDown();
                        assertThat(bothOuterPrechecks.await(5, TimeUnit.SECONDS)).isTrue();
                        return null;
                    }
                    return storedOperation.get();
                });
        when(userMapper.selectByIdForUpdate(OWNER_USER_ID)).thenAnswer(invocation -> {
            if (!actorRowLock.tryLock(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out acquiring the simulated actor row lock");
            }
            return activeUser(OWNER_USER_ID);
        });
        when(operationMapper.selectByActorAndIdempotencyKeyForUpdate(
                OWNER_USER_ID, IDEMPOTENCY_KEY)).thenAnswer(invocation -> {
                    DinnerHouseholdOperationEntity existing = storedOperation.get();
                    if (existing != null) {
                        actorRowLock.unlock();
                    }
                    return existing;
                });
        when(memberMapper.selectActiveByUserId(OWNER_USER_ID)).thenReturn(owner);
        when(householdMapper.selectByIdForUpdate(HOUSEHOLD_ID)).thenReturn(household);
        when(memberMapper.selectActiveByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of(owner, member));
        when(memberMapper.demoteActiveOwner(
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_VERSION)).thenReturn(1);
        when(memberMapper.promoteActiveMember(
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_VERSION)).thenReturn(1);
        when(householdMapper.advanceOwnership(
                HOUSEHOLD_ID, HOUSEHOLD_VERSION, NOW)).thenReturn(1);
        when(operationMapper.insert(any(DinnerHouseholdOperationEntity.class)))
                .thenAnswer(invocation -> {
                    storedOperation.set(invocation.getArgument(0));
                    actorRowLock.unlock();
                    return 1;
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HouseholdMutationResponse> first =
                    executor.submit(() -> orchestrator.transferOwnership(
                            OWNER_USER_ID,
                            OWNER_MEMBERSHIP_ID,
                            HOUSEHOLD_VERSION,
                            MEMBER_MEMBERSHIP_ID,
                            MEMBER_MEMBERSHIP_VERSION,
                            IDEMPOTENCY_KEY));
            Future<HouseholdMutationResponse> second =
                    executor.submit(() -> orchestrator.transferOwnership(
                            OWNER_USER_ID,
                            OWNER_MEMBERSHIP_ID,
                            HOUSEHOLD_VERSION,
                            MEMBER_MEMBERSHIP_ID,
                            MEMBER_MEMBERSHIP_VERSION,
                            IDEMPOTENCY_KEY));

            var results = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results)
                    .extracting(HouseholdMutationResponse::replayed)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results).allSatisfy(result -> {
                assertThat(result.operationType()).isEqualTo("OWNERSHIP_TRANSFER");
                assertThat(result.actorHasHousehold()).isTrue();
                assertThat(result.householdVersion()).isEqualTo(9L);
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            if (actorRowLock.isHeldByCurrentThread()) {
                actorRowLock.unlock();
            }
        }

        verify(memberMapper).demoteActiveOwner(
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_VERSION);
        verify(memberMapper).promoteActiveMember(
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_VERSION);
        verify(householdMapper).advanceOwnership(HOUSEHOLD_ID, HOUSEHOLD_VERSION, NOW);
        verify(operationMapper).insert(any(DinnerHouseholdOperationEntity.class));
    }

    private void stubLockedContext(
            Long actorUserId,
            DinnerHouseholdMemberEntity candidate,
            List<DinnerHouseholdMemberEntity> members
    ) {
        when(userMapper.selectByIdForUpdate(actorUserId)).thenReturn(activeUser(actorUserId));
        when(memberMapper.selectActiveByUserId(actorUserId)).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(HOUSEHOLD_ID)).thenReturn(household());
        when(memberMapper.selectActiveByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(members);
    }

    private HouseholdOperationCommand transferCommand() {
        return new HouseholdOperationCommand(
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                MEMBER_MEMBERSHIP_ID,
                MEMBER_MEMBERSHIP_VERSION,
                DinnerHouseholdOperationService.OWNERSHIP_TRANSFER,
                IDEMPOTENCY_KEY,
                FINGERPRINT);
    }

    private DinnerHouseholdEntity household() {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(HOUSEHOLD_ID);
        household.setName("我们的小家");
        household.setTimezone("Asia/Shanghai");
        household.setStatus("ACTIVE");
        household.setVersion(HOUSEHOLD_VERSION);
        household.setInviteRevision(5L);
        household.setCreatedBy(OWNER_USER_ID);
        return household;
    }

    private DinnerHouseholdMemberEntity ownerMembership() {
        return membership(
                OWNER_MEMBERSHIP_ID,
                OWNER_USER_ID,
                "OWNER",
                1,
                OWNER_MEMBERSHIP_VERSION);
    }

    private DinnerHouseholdMemberEntity memberMembership() {
        return membership(
                MEMBER_MEMBERSHIP_ID,
                MEMBER_USER_ID,
                "MEMBER",
                2,
                MEMBER_MEMBERSHIP_VERSION);
    }

    private DinnerHouseholdMemberEntity membership(
            Long id,
            Long userId,
            String role,
            int seat,
            Long version
    ) {
        DinnerHouseholdMemberEntity membership = new DinnerHouseholdMemberEntity();
        membership.setId(id);
        membership.setHouseholdId(HOUSEHOLD_ID);
        membership.setUserId(userId);
        membership.setRole(role);
        membership.setStatus("ACTIVE");
        membership.setSeatNo(seat);
        membership.setHistoryVisibleFrom(JOINED_AT);
        membership.setVersion(version);
        membership.setJoinedAt(JOINED_AT);
        return membership;
    }

    private UserEntity activeUser(Long userId) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setStatus("ACTIVE");
        return user;
    }

    private DinnerHouseholdOperationEntity operationResult() {
        DinnerHouseholdOperationEntity operation = new DinnerHouseholdOperationEntity();
        operation.setHouseholdId(HOUSEHOLD_ID);
        operation.setActorId(OWNER_USER_ID);
        operation.setActorMembershipId(OWNER_MEMBERSHIP_ID);
        operation.setTargetMemberId(MEMBER_MEMBERSHIP_ID);
        operation.setOperationType(DinnerHouseholdOperationService.OWNERSHIP_TRANSFER);
        operation.setIdempotencyKey(IDEMPOTENCY_KEY);
        operation.setRequestFingerprint(FINGERPRINT);
        operation.setResultSchemaVersion(1);
        operation.setResultHouseholdVersion(9L);
        operation.setResultPayload("{\"actorHasHousehold\":true}");
        operation.setCreatedAt(NOW.minusMinutes(1));
        operation.setExpiresAt(NOW.plusDays(1));
        return operation;
    }

    private void assertNoRoleMutations() {
        verify(memberMapper, never()).demoteActiveOwner(any(), any(), any(), any());
        verify(memberMapper, never()).promoteActiveMember(any(), any(), any(), any());
        verify(householdMapper, never()).advanceOwnership(any(), any(), any());
        verify(operationMapper, never()).insert(any(DinnerHouseholdOperationEntity.class));
    }

    private void assertBusinessError(ThrowingCallable callable, ErrorCode expected) {
        org.assertj.core.api.Assertions.assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(expected));
    }
}
