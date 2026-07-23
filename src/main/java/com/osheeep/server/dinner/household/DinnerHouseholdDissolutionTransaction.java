package com.osheeep.server.dinner.household;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerHouseholdDissolutionTransaction {

    private static final String ACTIVE = "ACTIVE";
    private static final String OWNER = "OWNER";

    private final UserMapper userMapper;
    private final WechatUserIdentityMapper identityMapper;
    private final DinnerHouseholdOperationMapper operationMapper;
    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerHouseholdDataPurger dataPurger;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DinnerHouseholdDissolutionTransaction(
            UserMapper userMapper,
            WechatUserIdentityMapper identityMapper,
            DinnerHouseholdOperationMapper operationMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdDataPurger dataPurger,
            ObjectMapper objectMapper
    ) {
        this(userMapper, identityMapper, operationMapper, householdMapper, memberMapper,
                dataPurger, objectMapper, Clock.systemUTC());
    }

    DinnerHouseholdDissolutionTransaction(
            UserMapper userMapper,
            WechatUserIdentityMapper identityMapper,
            DinnerHouseholdOperationMapper operationMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdDataPurger dataPurger,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.userMapper = userMapper;
        this.identityMapper = identityMapper;
        this.operationMapper = operationMapper;
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.dataPurger = dataPurger;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HouseholdMutationResponse dissolve(
            HouseholdOperationCommand command,
            String normalizedConfirmationName,
            String verifiedOpenid
    ) {
        try {
            return dissolveLocked(command, normalizedConfirmationName, verifiedOpenid);
        } catch (PessimisticLockingFailureException exception) {
            throw householdVersionConflict();
        }
    }

    private HouseholdMutationResponse dissolveLocked(
            HouseholdOperationCommand command,
            String normalizedConfirmationName,
            String verifiedOpenid
    ) {
        if (!DinnerHouseholdDissolutionService.HOUSEHOLD_DISSOLUTION.equals(
                command.operationType())) {
            throw new IllegalArgumentException("Unsupported household operation");
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

        WechatUserIdentityEntity identity =
                identityMapper.selectByUserIdForUpdate(command.actorUserId());
        if (identity == null
                || identity.getId() == null
                || !Objects.equals(command.actorUserId(), identity.getUserId())
                || !StringUtils.hasText(verifiedOpenid)
                || !Objects.equals(identity.getOpenid(), verifiedOpenid)) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_IDENTITY_MISMATCH);
        }

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
        List<DinnerHouseholdMemberEntity> memberships =
                memberMapper.selectAllByHouseholdIdForUpdate(household.getId());
        DinnerHouseholdMemberEntity actorMembership = validateMembershipSet(
                memberships, household.getId(), command.actorUserId());
        if (!sameSnapshot(candidate, actorMembership)) {
            throw householdVersionConflict();
        }
        if (!Objects.equals(command.actorMembershipId(), actorMembership.getId())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
        }
        if (!Objects.equals(command.expectedHouseholdVersion(), household.getVersion())) {
            throw householdVersionConflict();
        }
        if (!OWNER.equals(actorMembership.getRole())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_OWNER_REQUIRED);
        }
        if (!Objects.equals(normalizedConfirmationName, household.getName())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_NAME_MISMATCH);
        }

        Long householdId = household.getId();
        dataPurger.purgeHousehold(householdId, memberships, Set.of());
        persistResult(command, householdId, now);
        return new HouseholdMutationResponse(
                command.operationType(), false, false, null);
    }

    private DinnerHouseholdMemberEntity validateMembershipSet(
            List<DinnerHouseholdMemberEntity> memberships,
            Long householdId,
            Long actorUserId
    ) {
        if (memberships == null || memberships.isEmpty()) {
            throw householdVersionConflict();
        }
        Long previousId = null;
        Set<Integer> activeSeats = new HashSet<>();
        int activeCount = 0;
        int ownerCount = 0;
        DinnerHouseholdMemberEntity actor = null;
        for (DinnerHouseholdMemberEntity membership : memberships) {
            if (membership == null
                    || membership.getId() == null
                    || !Objects.equals(householdId, membership.getHouseholdId())
                    || previousId != null && membership.getId() <= previousId) {
                throw householdVersionConflict();
            }
            previousId = membership.getId();
            if (!ACTIVE.equals(membership.getStatus())) {
                continue;
            }
            if (!isCompleteActiveMember(membership, householdId)
                    || !activeSeats.add(membership.getSeatNo())) {
                throw householdVersionConflict();
            }
            activeCount++;
            if (OWNER.equals(membership.getRole())) {
                ownerCount++;
            }
            if (Objects.equals(actorUserId, membership.getUserId())) {
                if (actor != null) {
                    throw householdVersionConflict();
                }
                actor = membership;
            }
        }
        if (activeCount < 1 || activeCount > 2 || ownerCount != 1) {
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
        return member.getId() != null
                && Objects.equals(householdId, member.getHouseholdId())
                && member.getUserId() != null
                && (OWNER.equals(member.getRole()) || "MEMBER".equals(member.getRole()))
                && member.getSeatNo() != null
                && member.getSeatNo() >= 1
                && member.getSeatNo() <= 2
                && member.getVersion() != null
                && member.getVersion() >= 1
                && member.getHistoryVisibleFrom() != null;
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
                && Objects.equals(candidate.getHistoryVisibleFrom(), locked.getHistoryVisibleFrom());
    }

    private boolean isActiveHousehold(DinnerHouseholdEntity household, Long householdId) {
        return household != null
                && Objects.equals(householdId, household.getId())
                && ACTIVE.equals(household.getStatus())
                && StringUtils.hasText(household.getName())
                && StringUtils.hasText(household.getTimezone())
                && household.getVersion() != null
                && household.getVersion() >= 1
                && household.getInviteRevision() != null
                && household.getInviteRevision() >= 0;
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
            LocalDateTime now
    ) {
        DinnerHouseholdOperationEntity operation = new DinnerHouseholdOperationEntity();
        operation.setHouseholdId(householdId);
        operation.setActorId(command.actorUserId());
        operation.setActorMembershipId(command.actorMembershipId());
        operation.setTargetMemberId(null);
        operation.setOperationType(command.operationType());
        operation.setIdempotencyKey(command.idempotencyKey());
        operation.setRequestFingerprint(command.fingerprint());
        operation.setResultSchemaVersion(1);
        operation.setResultHouseholdVersion(null);
        operation.setResultPayload("{\"actorHasHousehold\":false}");
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
}
