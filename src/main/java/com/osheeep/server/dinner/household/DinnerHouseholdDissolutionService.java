package com.osheeep.server.dinner.household;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.auth.wechat.WechatCode2SessionClient;
import com.osheeep.server.auth.wechat.WechatSession;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdOperationService.HouseholdOperationCommand;
import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DinnerHouseholdDissolutionService {

    static final String HOUSEHOLD_DISSOLUTION = "HOUSEHOLD_DISSOLUTION";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(DinnerHouseholdDissolutionService.class);

    private final WechatCode2SessionClient sessionClient;
    private final DinnerHouseholdOperationMapper operationMapper;
    private final HouseholdOperationFingerprinter fingerprinter;
    private final DinnerHouseholdOperationRetentionService retentionService;
    private final DinnerHouseholdNameService nameService;
    private final DinnerHouseholdDissolutionTransaction dissolutionTransaction;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DinnerHouseholdDissolutionService(
            WechatCode2SessionClient sessionClient,
            DinnerHouseholdOperationMapper operationMapper,
            HouseholdOperationFingerprinter fingerprinter,
            DinnerHouseholdOperationRetentionService retentionService,
            DinnerHouseholdNameService nameService,
            DinnerHouseholdDissolutionTransaction dissolutionTransaction,
            ObjectMapper objectMapper
    ) {
        this(sessionClient, operationMapper, fingerprinter, retentionService, nameService,
                dissolutionTransaction, objectMapper, Clock.systemUTC());
    }

    DinnerHouseholdDissolutionService(
            WechatCode2SessionClient sessionClient,
            DinnerHouseholdOperationMapper operationMapper,
            HouseholdOperationFingerprinter fingerprinter,
            DinnerHouseholdOperationRetentionService retentionService,
            DinnerHouseholdNameService nameService,
            DinnerHouseholdDissolutionTransaction dissolutionTransaction,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.sessionClient = sessionClient;
        this.operationMapper = operationMapper;
        this.fingerprinter = fingerprinter;
        this.retentionService = retentionService;
        this.nameService = nameService;
        this.dissolutionTransaction = dissolutionTransaction;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public HouseholdMutationResponse dissolve(
            Long actorUserId,
            Long actorMembershipId,
            Long expectedHouseholdVersion,
            String confirmationName,
            String code,
            String idempotencyKey
    ) {
        String normalizedName = nameService.normalize(confirmationName);
        HouseholdOperationCommand command = command(
                actorUserId, actorMembershipId, expectedHouseholdVersion,
                normalizedName, idempotencyKey);
        cleanupExpiredBestEffort();

        DinnerHouseholdOperationEntity existing =
                operationMapper.selectByActorAndIdempotencyKey(
                        command.actorUserId(), command.idempotencyKey());
        if (existing != null
                && !DinnerHouseholdOperationService.isExpired(existing, utcNow())) {
            return DinnerHouseholdOperationService.replay(command, existing, objectMapper);
        }

        WechatSession session = sessionClient.exchange(code);
        return dissolutionTransaction.dissolve(command, normalizedName, session.openid());
    }

    private HouseholdOperationCommand command(
            Long actorUserId,
            Long actorMembershipId,
            Long expectedHouseholdVersion,
            String normalizedName,
            String idempotencyKey
    ) {
        if (actorUserId == null || actorUserId < 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            String normalizedKey = fingerprinter.normalizeIdempotencyKey(idempotencyKey);
            String fingerprint = fingerprinter.fingerprint(
                    HOUSEHOLD_DISSOLUTION,
                    actorMembershipId,
                    expectedHouseholdVersion,
                    null,
                    null,
                    normalizedName);
            return new HouseholdOperationCommand(
                    actorUserId,
                    actorMembershipId,
                    expectedHouseholdVersion,
                    null,
                    null,
                    HOUSEHOLD_DISSOLUTION,
                    normalizedKey,
                    fingerprint);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    ErrorCode.VALIDATION_ERROR.defaultMessage());
        }
    }

    private void cleanupExpiredBestEffort() {
        try {
            retentionService.cleanupExpiredBatch();
        } catch (RuntimeException exception) {
            LOGGER.warn("Household operation retention cleanup failed");
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }
}
