package com.osheeep.server.dinner.subscription;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionActionResponse;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionConfigResponse;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionResultItemRequest;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionResultRequest;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionTemplateResponse;
import com.osheeep.server.dinner.subscription.entity.DinnerSubscriptionDeliveryEntity;
import com.osheeep.server.dinner.subscription.mapper.DinnerSubscriptionDeliveryMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerSubscriptionService {

    private static final long RETENTION_DAYS = 90L;

    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerSubscriptionDeliveryMapper deliveryMapper;
    private final WechatSubscriptionProperties properties;
    private final Clock clock;

    @Autowired
    public DinnerSubscriptionService(
            DinnerHouseholdMemberMapper memberMapper,
            DinnerSubscriptionDeliveryMapper deliveryMapper,
            WechatSubscriptionProperties properties
    ) {
        this(memberMapper, deliveryMapper, properties, Clock.systemUTC());
    }

    DinnerSubscriptionService(
            DinnerHouseholdMemberMapper memberMapper,
            DinnerSubscriptionDeliveryMapper deliveryMapper,
            WechatSubscriptionProperties properties,
            Clock clock
    ) {
        this.memberMapper = memberMapper;
        this.deliveryMapper = deliveryMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public DinnerSubscriptionConfigResponse config(Long userId) {
        requirePositive(userId);
        if (!properties.enabled()) {
            return new DinnerSubscriptionConfigResponse(List.of());
        }
        DinnerHouseholdMemberEntity membership = activeMembership(userId);
        if (membership == null) {
            return new DinnerSubscriptionConfigResponse(List.of());
        }
        LocalDateTime now = now();
        List<String> storedBlocking = deliveryMapper.selectBlockingScenarios(
                userId, membership.getHouseholdId(), now);
        Set<String> blocking = storedBlocking == null
                ? Set.of()
                : Set.copyOf(storedBlocking);
        List<DinnerSubscriptionActionResponse> actions = new ArrayList<>();
        for (DinnerSubscriptionAction action : DinnerSubscriptionAction.values()) {
            List<DinnerSubscriptionTemplateResponse> templates =
                    action.scenarios().stream()
                            .filter(scenario -> !blocking.contains(scenario.name()))
                            .map(scenario -> new DinnerSubscriptionTemplateResponse(
                                    scenario.name(),
                                    properties.template(scenario).id()))
                            .toList();
            if (!templates.isEmpty()) {
                actions.add(new DinnerSubscriptionActionResponse(
                        action.name(), templates));
            }
        }
        return new DinnerSubscriptionConfigResponse(List.copyOf(actions));
    }

    @Transactional
    public void recordResults(
            Long userId,
            DinnerSubscriptionResultRequest request
    ) {
        requirePositive(userId);
        if (!properties.enabled()
                || request == null
                || request.requestId() == null
                || request.requestId().version() != 4
                || request.requestId().variant() != 2) {
            throw validation();
        }
        DinnerHouseholdMemberEntity membership = activeMembership(userId);
        if (membership == null) {
            throw validation();
        }
        DinnerSubscriptionAction action = parseAction(request.action());
        if (request.results() == null
                || request.results().isEmpty()
                || request.results().size() > 5) {
            throw validation();
        }
        Set<DinnerSubscriptionScenario> seen = new HashSet<>();
        LocalDateTime createdAt = now();
        for (DinnerSubscriptionResultItemRequest item : request.results()) {
            DinnerSubscriptionScenario scenario = parseScenario(item);
            DinnerSubscriptionOutcome outcome = parseOutcome(item);
            if (!action.scenarios().contains(scenario) || !seen.add(scenario)) {
                throw validation();
            }
            DinnerSubscriptionDeliveryEntity row =
                    new DinnerSubscriptionDeliveryEntity();
            row.setRecipientId(userId);
            row.setHouseholdId(membership.getHouseholdId());
            row.setScenario(scenario.name());
            row.setRequestKey(request.requestId().toString());
            row.setOutcome(outcome.name());
            row.setStatus(outcome == DinnerSubscriptionOutcome.ACCEPT
                    ? "WAITING_EVENT"
                    : "REJECTED");
            row.setAttemptCount(0);
            row.setCreatedAt(createdAt);
            row.setUpdatedAt(createdAt);
            row.setExpiresAt(createdAt.plusDays(RETENTION_DAYS));
            try {
                if (deliveryMapper.insert(row) != 1) {
                    throw new IllegalStateException(
                            "Subscription result was not stored");
                }
            } catch (DuplicateKeyException ignored) {
                // A retry of the same native subscription result is already complete.
            }
        }
    }

    private DinnerHouseholdMemberEntity activeMembership(Long userId) {
        DinnerHouseholdMemberEntity membership =
                memberMapper.selectActiveByUserId(userId);
        if (membership == null) {
            return null;
        }
        if (membership.getId() == null
                || !userId.equals(membership.getUserId())
                || membership.getHouseholdId() == null
                || membership.getHouseholdId() <= 0
                || !"ACTIVE".equals(membership.getStatus())) {
            throw new BusinessException(
                    ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
        }
        return membership;
    }

    private DinnerSubscriptionAction parseAction(String value) {
        try {
            return DinnerSubscriptionAction.valueOf(value);
        } catch (RuntimeException exception) {
            throw validation();
        }
    }

    private DinnerSubscriptionScenario parseScenario(
            DinnerSubscriptionResultItemRequest item
    ) {
        try {
            return DinnerSubscriptionScenario.valueOf(item.scenario());
        } catch (RuntimeException exception) {
            throw validation();
        }
    }

    private DinnerSubscriptionOutcome parseOutcome(
            DinnerSubscriptionResultItemRequest item
    ) {
        try {
            return DinnerSubscriptionOutcome.valueOf(item.outcome());
        } catch (RuntimeException exception) {
            throw validation();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private void requirePositive(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User id must be positive");
        }
    }

    private BusinessException validation() {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "Invalid dinner subscription result");
    }
}
