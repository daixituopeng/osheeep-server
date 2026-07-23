package com.osheeep.server.dinner.subscription;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DinnerSubscriptionRetentionScheduler {

    private final DinnerSubscriptionRetentionService service;

    public DinnerSubscriptionRetentionScheduler(
            DinnerSubscriptionRetentionService service
    ) {
        this.service = service;
    }

    @Scheduled(cron = "0 47 3 * * *", zone = "UTC")
    public void deleteExpired() {
        service.deleteExpired();
    }
}
