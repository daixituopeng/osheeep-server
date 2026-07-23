package com.osheeep.server.dinner.subscription;

import java.time.LocalDateTime;

public record DinnerSubscriptionDeliveryClaim(
        Long id,
        Long recipientId,
        String scenario,
        String notificationType,
        int attemptCount,
        LocalDateTime eventAt
) {
}
