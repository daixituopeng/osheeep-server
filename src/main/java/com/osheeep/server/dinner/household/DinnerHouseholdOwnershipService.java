package com.osheeep.server.dinner.household;

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
import com.osheeep.server.dinner.notification.DinnerNotificationPublisher;
import com.osheeep.server.dinner.notification.DinnerNotificationReferenceType;
import com.osheeep.server.dinner.notification.DinnerNotificationType;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerHouseholdOwnershipService {

    private static final String ACTIVE = "ACTIVE";
    private static final String OWNER = "OWNER";
    private static final String MEMBER = "MEMBER";

    private final UserMapper userMapper;
    private final DinnerHouseholdOperationMapper operationMapper;
    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private DinnerNotificationPublisher notificationPublisher =
            DinnerNotificationPublisher.noop();

    @Autowired
    public DinnerHouseholdOwnershipService(
            UserMapper userMapper,
            DinnerHouseholdOperationMapper operationMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            ObjectMapper objectMapper
    ) {
        this(userMapper, operationMapper, householdMapper, memberMapper,
                objectMapper, Clock.systemUTC());
    }

    DinnerHouseholdOwnershipService(
            UserMapper userMapper,
            DinnerHouseholdOperationMapper operationMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.userMapper = userMapper;
        this.operationMapper = operationMapper;
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Autowired(required = false)
    void setNotificationPublisher(DinnerNotificationPublisher notificationPublisher) {
        this.notificationPublisher = Objects.requireNonNull(notificationPublisher);
    }

    @Transactional
    public HouseholdMutationResponse transfer(HouseholdOperationCommand command) {
        try {
            return transferLocked(command);
        } catch (PessimisticLockingFailureException exception) {
            throw householdVersionConflict();
        }
    }

    private HouseholdMutationResponse transferLocked(HouseholdOperationCommand command) {
        if (!DinnerHouseholdOperationService.OWNERSHIP_TRANSFER.equals(
                command.operationType())) {
            throw new IllegalArgumentException("Unsupported ownership operation");
        }

        UserEntity actor = userMapper.selectByIdForUpdate(command.actorUserId());
        if (!isActiveActor(actor, command.actorUserId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User is not available");
        }

        LocalDateTime now = utcNow();
        DinnerHouseholdOperationEntity existing =
                operationMapper.selectByActorAndIdempotencyKeyForUpdate(
                        command.actorUserId(), command.idempotencyKey());
        if (existing != null) {
            if (!DinnerHouseholdOperationService.isExpired(existing, now)) {
                return DinnerHouseholdOperationService.replay(command, existing, objectMapper);
            }
            if (operationMapper.deleteExpiredByActorAndIdempotencyKey(
                    command.actorUserId(), command.idempotencyKey(), now) != 1) {
                throw householdVersionConflict();
            }
        }

        LockedOwnershipContext context = lockAndValidateContext(command);
        DinnerHouseholdEntity household = context.household();
        DinnerHouseholdMemberEntity owner = context.owner();
        DinnerHouseholdMemberEntity target = context.target();

        // Demote first so the generated unique ACTIVE-owner key never observes two owners.
        if (memberMapper.demoteActiveOwner(
                owner.getId(), household.getId(), owner.getUserId(), owner.getVersion()) != 1) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
        }
        if (memberMapper.promoteActiveMember(
                target.getId(), household.getId(), target.getUserId(), target.getVersion()) != 1) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
        }
        if (householdMapper.advanceOwnership(
                household.getId(), household.getVersion(), now) != 1) {
            throw householdVersionConflict();
        }

        long resultHouseholdVersion = Math.addExact(household.getVersion(), 1L);
        persistResult(command, household.getId(), resultHouseholdVersion, now);
        notificationPublisher.toPartner(
                household.getId(),
                command.actorUserId(),
                DinnerNotificationType.OWNERSHIP_TRANSFERRED,
                DinnerNotificationReferenceType.HOUSEHOLD_OPERATION,
                household.getId(),
                resultHouseholdVersion,
                "household-operation:" + command.idempotencyKey());
        return new HouseholdMutationResponse(
                command.operationType(), false, true, resultHouseholdVersion);
    }

    private LockedOwnershipContext lockAndValidateContext(HouseholdOperationCommand command) {
        DinnerHouseholdMemberEntity candidate =
                memberMapper.selectActiveByUserId(command.actorUserId());
        if (!isCandidate(candidate, command.actorUserId())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
        }

        DinnerHouseholdEntity household =
                householdMapper.selectByIdForUpdate(candidate.getHouseholdId());
        if (!isActiveHousehold(household, candidate.getHouseholdId())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
        }
        List<DinnerHouseholdMemberEntity> members =
                memberMapper.selectActiveByHouseholdIdForUpdate(household.getId());
        DinnerHouseholdMemberEntity owner = validateMembershipSet(
                members, household.getId(), command.actorUserId());
        if (!sameSnapshot(candidate, owner)) {
            throw householdVersionConflict();
        }
        if (!Objects.equals(command.actorMembershipId(), owner.getId())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
        }
        if (!Objects.equals(command.expectedHouseholdVersion(), household.getVersion())) {
            throw householdVersionConflict();
        }
        if (!OWNER.equals(owner.getRole())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_OWNER_REQUIRED);
        }
        if (Objects.equals(command.targetMembershipId(), owner.getId())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
        }

        DinnerHouseholdMemberEntity target = members.stream()
                .filter(member -> Objects.equals(command.targetMembershipId(), member.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.DINNER_HOUSEHOLD_MEMBER_NOT_FOUND));
        if (members.size() != 2
                || !MEMBER.equals(target.getRole())
                || !Objects.equals(command.targetMembershipVersion(), target.getVersion())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
        }
        return new LockedOwnershipContext(household, owner, target);
    }

    private DinnerHouseholdMemberEntity validateMembershipSet(
            List<DinnerHouseholdMemberEntity> members,
            Long householdId,
            Long actorUserId
    ) {
        if (members == null || members.isEmpty() || members.size() > 2) {
            throw householdVersionConflict();
        }
        Long previousId = null;
        Set<Integer> seats = new HashSet<>();
        int ownerCount = 0;
        DinnerHouseholdMemberEntity actor = null;
        for (DinnerHouseholdMemberEntity member : members) {
            if (!isCompleteActiveMember(member, householdId)
                    || (previousId != null && member.getId() <= previousId)
                    || !seats.add(member.getSeatNo())) {
                throw householdVersionConflict();
            }
            if (OWNER.equals(member.getRole())) {
                ownerCount++;
            }
            if (Objects.equals(actorUserId, member.getUserId())) {
                if (actor != null) {
                    throw householdVersionConflict();
                }
                actor = member;
            }
            previousId = member.getId();
        }
        if (ownerCount != 1) {
            throw householdVersionConflict();
        }
        if (actor == null) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
        }
        return actor;
    }

    private boolean isCandidate(DinnerHouseholdMemberEntity candidate, Long actorUserId) {
        return candidate != null
                && candidate.getId() != null
                && candidate.getHouseholdId() != null
                && Objects.equals(actorUserId, candidate.getUserId())
                && ACTIVE.equals(candidate.getStatus());
    }

    private boolean isCompleteActiveMember(
            DinnerHouseholdMemberEntity member,
            Long householdId
    ) {
        return member != null
                && member.getId() != null
                && Objects.equals(householdId, member.getHouseholdId())
                && member.getUserId() != null
                && ACTIVE.equals(member.getStatus())
                && (OWNER.equals(member.getRole()) || MEMBER.equals(member.getRole()))
                && member.getSeatNo() != null
                && member.getSeatNo() >= 1
                && member.getSeatNo() <= 2
                && member.getVersion() != null
                && member.getVersion() >= 1
                && member.getHistoryVisibleFrom() != null;
    }

    private boolean isActiveHousehold(DinnerHouseholdEntity household, Long householdId) {
        return household != null
                && Objects.equals(householdId, household.getId())
                && ACTIVE.equals(household.getStatus())
                && household.getVersion() != null
                && household.getVersion() >= 1
                && household.getInviteRevision() != null
                && household.getInviteRevision() >= 0
                && StringUtils.hasText(household.getTimezone());
    }

    private boolean sameSnapshot(
            DinnerHouseholdMemberEntity candidate,
            DinnerHouseholdMemberEntity locked
    ) {
        return Objects.equals(candidate.getId(), locked.getId())
                && Objects.equals(candidate.getHouseholdId(), locked.getHouseholdId())
                && Objects.equals(candidate.getUserId(), locked.getUserId())
                && Objects.equals(candidate.getRole(), locked.getRole())
                && Objects.equals(candidate.getStatus(), locked.getStatus())
                && Objects.equals(candidate.getSeatNo(), locked.getSeatNo())
                && Objects.equals(candidate.getVersion(), locked.getVersion())
                && Objects.equals(
                        candidate.getHistoryVisibleFrom(), locked.getHistoryVisibleFrom());
    }

    private boolean isActiveActor(UserEntity actor, Long actorUserId) {
        return actor != null
                && Objects.equals(actorUserId, actor.getId())
                && ACTIVE.equals(actor.getStatus())
                && actor.getDeletedAt() == null;
    }

    private void persistResult(
            HouseholdOperationCommand command,
            Long householdId,
            Long resultHouseholdVersion,
            LocalDateTime now
    ) {
        DinnerHouseholdOperationEntity operation = new DinnerHouseholdOperationEntity();
        operation.setHouseholdId(householdId);
        operation.setActorId(command.actorUserId());
        operation.setActorMembershipId(command.actorMembershipId());
        operation.setTargetMemberId(command.targetMembershipId());
        operation.setOperationType(command.operationType());
        operation.setIdempotencyKey(command.idempotencyKey());
        operation.setRequestFingerprint(command.fingerprint());
        operation.setResultSchemaVersion(1);
        operation.setResultHouseholdVersion(resultHouseholdVersion);
        operation.setResultPayload("{\"actorHasHousehold\":true}");
        operation.setCreatedAt(now);
        operation.setExpiresAt(now.plusDays(14));
        if (operationMapper.insert(operation) != 1) {
            throw new IllegalStateException("Household operation result was not stored");
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private BusinessException householdVersionConflict() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    private record LockedOwnershipContext(
            DinnerHouseholdEntity household,
            DinnerHouseholdMemberEntity owner,
            DinnerHouseholdMemberEntity target
    ) {
    }
}
