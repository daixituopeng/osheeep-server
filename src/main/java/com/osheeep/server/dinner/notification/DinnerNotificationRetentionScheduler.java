package com.osheeep.server.dinner.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DinnerNotificationRetentionScheduler {

    private final DinnerNotificationRetentionService service;

    public DinnerNotificationRetentionScheduler(
            DinnerNotificationRetentionService service
    ) {
        this.service = service;
    }

    @Scheduled(cron = "0 37 3 * * *", zone = "UTC")
    public void deleteExpired() {
        service.deleteExpired();
    }
}
