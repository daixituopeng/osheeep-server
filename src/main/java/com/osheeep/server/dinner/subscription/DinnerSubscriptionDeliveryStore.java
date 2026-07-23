package com.osheeep.server.dinner.subscription;

import com.osheeep.server.dinner.subscription.entity.DinnerSubscriptionDeliveryEntity;
import com.osheeep.server.dinner.subscription.mapper.DinnerSubscriptionDeliveryMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerSubscriptionDeliveryStore {

    private static final long STALE_CLAIM_MINUTES = 5L;
    private static final int MAX_ATTEMPTS = 5;

    private final DinnerSubscriptionDeliveryMapper mapper;

    public DinnerSubscriptionDeliveryStore(
            DinnerSubscriptionDeliveryMapper mapper
    ) {
        this.mapper = mapper;
    }

    @Transactional
    public DinnerSubscriptionDeliveryClaim claimNext(LocalDateTime now) {
        DinnerSubscriptionDeliveryEntity row =
                mapper.selectNextClaimableForUpdate(
                        now, now.minusMinutes(STALE_CLAIM_MINUTES));
        if (row == null) {
            return null;
        }
        int previousAttempt = row.getAttemptCount() == null
                ? 0
                : row.getAttemptCount();
        if (row.getId() == null
                || row.getRecipientId() == null
                || row.getScenario() == null
                || row.getNotificationType() == null
                || row.getUpdatedAt() == null
                || previousAttempt < 0
                || !("READY".equals(row.getStatus())
                || "SENDING".equals(row.getStatus()))) {
            throw new IllegalStateException("Invalid claimable subscription delivery");
        }
        if (previousAttempt >= MAX_ATTEMPTS) {
            if (!"SENDING".equals(row.getStatus())
                    || mapper.markTerminal(
                    row.getId(), previousAttempt, null, now) != 1) {
                throw new IllegalStateException(
                        "Exhausted subscription delivery was not terminated");
            }
            return null;
        }
        int attempt = previousAttempt + 1;
        if (mapper.markSending(
                row.getId(),
                row.getStatus(),
                previousAttempt,
                attempt,
                now) != 1) {
            throw new IllegalStateException("Subscription delivery was not claimed");
        }
        return new DinnerSubscriptionDeliveryClaim(
                row.getId(),
                row.getRecipientId(),
                row.getScenario(),
                row.getNotificationType(),
                attempt,
                row.getUpdatedAt());
    }

    @Transactional
    public void markSent(Long id, int attemptCount, LocalDateTime sentAt) {
        if (mapper.markSent(id, attemptCount, sentAt) != 1) {
            throw new IllegalStateException("Subscription delivery was not marked sent");
        }
    }

    @Transactional
    public void markRetry(
            Long id,
            int attemptCount,
            Integer errorCode,
            LocalDateTime nextAttemptAt,
            LocalDateTime updatedAt
    ) {
        if (mapper.markRetry(
                id, attemptCount, errorCode, nextAttemptAt, updatedAt) != 1) {
            throw new IllegalStateException("Subscription retry was not scheduled");
        }
    }

    @Transactional
    public void markTerminal(
            Long id,
            int attemptCount,
            Integer errorCode,
            LocalDateTime updatedAt
    ) {
        if (mapper.markTerminal(id, attemptCount, errorCode, updatedAt) != 1) {
            throw new IllegalStateException(
                    "Subscription delivery was not marked terminal");
        }
    }
}
