package com.osheeep.server.dinner.subscription;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.dinner.notification.DinnerNotificationCommittedEvent;
import com.osheeep.server.dinner.notification.DinnerNotificationReferenceType;
import com.osheeep.server.dinner.notification.DinnerNotificationType;
import com.osheeep.server.dinner.subscription.entity.DinnerSubscriptionDeliveryEntity;
import com.osheeep.server.dinner.subscription.mapper.DinnerSubscriptionDeliveryMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DinnerSubscriptionEventCaptureServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-23T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-07-23T10:00:00");

    private final DinnerSubscriptionDeliveryMapper mapper =
            mock(DinnerSubscriptionDeliveryMapper.class);
    private final DinnerSubscriptionEventCaptureService service =
            new DinnerSubscriptionEventCaptureService(mapper, CLOCK);

    @Test
    void consumesOneMatchingGrantIntoAReadyDelivery() {
        DinnerSubscriptionDeliveryEntity waiting = new DinnerSubscriptionDeliveryEntity();
        waiting.setId(501L);
        waiting.setRecipientId(8L);
        waiting.setHouseholdId(11L);
        waiting.setScenario("MENU_CHANGED");
        waiting.setStatus("WAITING_EVENT");
        when(mapper.selectWaitingForUpdate(8L, 11L, "MENU_CHANGED", NOW))
                .thenReturn(waiting);
        when(mapper.markReady(
                501L,
                "MENU_RECONFIRM_REQUIRED",
                "MENU",
                81L,
                6L,
                "event-dedupe",
                NOW,
                NOW))
                .thenReturn(1);

        service.capture(event(DinnerNotificationType.MENU_RECONFIRM_REQUIRED));

        verify(mapper).markReady(
                501L,
                "MENU_RECONFIRM_REQUIRED",
                "MENU",
                81L,
                6L,
                "event-dedupe",
                NOW,
                NOW);
    }

    @Test
    void ignoresEventsWithoutASubscriptionScenarioOrWaitingGrant() {
        service.capture(event(DinnerNotificationType.INVENTORY_UPDATED));
        verify(mapper, never()).selectWaitingForUpdate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        when(mapper.selectWaitingForUpdate(8L, 11L, "MENU_CHANGED", NOW))
                .thenReturn(null);
        service.capture(event(DinnerNotificationType.PARTNER_SELECTION_UPDATED));
        verify(mapper, never()).markReady(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private DinnerNotificationCommittedEvent event(DinnerNotificationType type) {
        return new DinnerNotificationCommittedEvent(
                8L,
                11L,
                type,
                DinnerNotificationReferenceType.MENU,
                81L,
                6L,
                "event-dedupe");
    }
}
