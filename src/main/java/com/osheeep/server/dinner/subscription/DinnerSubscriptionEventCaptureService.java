package com.osheeep.server.dinner.subscription;

import com.osheeep.server.dinner.notification.DinnerNotificationCommittedEvent;
import com.osheeep.server.dinner.subscription.entity.DinnerSubscriptionDeliveryEntity;
import com.osheeep.server.dinner.subscription.mapper.DinnerSubscriptionDeliveryMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerSubscriptionEventCaptureService {

    private final DinnerSubscriptionDeliveryMapper mapper;
    private final Clock clock;

    @Autowired
    public DinnerSubscriptionEventCaptureService(
            DinnerSubscriptionDeliveryMapper mapper
    ) {
        this(mapper, Clock.systemUTC());
    }

    DinnerSubscriptionEventCaptureService(
            DinnerSubscriptionDeliveryMapper mapper,
            Clock clock
    ) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void capture(DinnerNotificationCommittedEvent event) {
        if (event == null || event.type() == null) {
            return;
        }
        DinnerSubscriptionScenario scenario =
                DinnerSubscriptionScenario.forEvent(event.type());
        if (scenario == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
        DinnerSubscriptionDeliveryEntity waiting =
                mapper.selectWaitingForUpdate(
                        event.recipientUserId(),
                        event.householdId(),
                        scenario.name(),
                        now);
        if (waiting == null) {
            return;
        }
        if (waiting.getId() == null
                || !event.recipientUserId().equals(waiting.getRecipientId())
                || !event.householdId().equals(waiting.getHouseholdId())
                || !scenario.name().equals(waiting.getScenario())
                || !"WAITING_EVENT".equals(waiting.getStatus())) {
            throw new IllegalStateException("Invalid waiting subscription delivery");
        }
        if (mapper.markReady(
                waiting.getId(),
                event.type().name(),
                event.referenceType().name(),
                event.referenceId(),
                event.referenceVersion(),
                event.eventDedupeKey(),
                now,
                now) != 1) {
            throw new IllegalStateException("Subscription delivery was not queued");
        }
    }
}
