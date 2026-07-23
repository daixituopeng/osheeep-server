package com.osheeep.server.dinner.subscription;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DinnerSubscriptionDeliveryScheduler {

    private static final int MAX_BATCH = 20;

    private final DinnerSubscriptionDeliveryProcessor processor;

    public DinnerSubscriptionDeliveryScheduler(
            DinnerSubscriptionDeliveryProcessor processor
    ) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void deliverDueMessages() {
        for (int index = 0; index < MAX_BATCH; index++) {
            if (!processor.processNext()) {
                return;
            }
        }
    }
}
