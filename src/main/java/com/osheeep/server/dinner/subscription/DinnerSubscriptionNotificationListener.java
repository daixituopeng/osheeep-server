package com.osheeep.server.dinner.subscription;

import com.osheeep.server.dinner.notification.DinnerNotificationCommittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DinnerSubscriptionNotificationListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DinnerSubscriptionNotificationListener.class);

    private final DinnerSubscriptionEventCaptureService captureService;

    public DinnerSubscriptionNotificationListener(
            DinnerSubscriptionEventCaptureService captureService
    ) {
        this.captureService = captureService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterNotificationCommit(DinnerNotificationCommittedEvent event) {
        try {
            captureService.capture(event);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Optional WeChat subscription event capture failed for type={}",
                    event == null || event.type() == null
                            ? "UNKNOWN"
                            : event.type().name());
        }
    }
}
