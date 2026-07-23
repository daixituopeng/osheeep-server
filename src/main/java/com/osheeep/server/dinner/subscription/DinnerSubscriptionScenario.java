package com.osheeep.server.dinner.subscription;

import com.osheeep.server.dinner.notification.DinnerNotificationType;
import java.util.EnumSet;
import java.util.Set;

public enum DinnerSubscriptionScenario {
    PARTNER_JOINED(EnumSet.of(DinnerNotificationType.PARTNER_JOINED)),
    MENU_CHANGED(EnumSet.of(
            DinnerNotificationType.PARTNER_SELECTION_UPDATED,
            DinnerNotificationType.MENU_RECONFIRM_REQUIRED)),
    MENU_COMPLETED(EnumSet.of(DinnerNotificationType.MENU_COMPLETED));

    private final Set<DinnerNotificationType> eventTypes;

    DinnerSubscriptionScenario(Set<DinnerNotificationType> eventTypes) {
        this.eventTypes = Set.copyOf(eventTypes);
    }

    public boolean accepts(DinnerNotificationType type) {
        return eventTypes.contains(type);
    }

    public static DinnerSubscriptionScenario forEvent(DinnerNotificationType type) {
        for (DinnerSubscriptionScenario scenario : values()) {
            if (scenario.accepts(type)) {
                return scenario;
            }
        }
        return null;
    }
}
