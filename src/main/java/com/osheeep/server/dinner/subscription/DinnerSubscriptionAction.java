package com.osheeep.server.dinner.subscription;

import java.util.List;

public enum DinnerSubscriptionAction {
    HOUSEHOLD_INVITE_READY(List.of(DinnerSubscriptionScenario.PARTNER_JOINED)),
    MENU_CONFIRMED(List.of(
            DinnerSubscriptionScenario.MENU_CHANGED,
            DinnerSubscriptionScenario.MENU_COMPLETED));

    private final List<DinnerSubscriptionScenario> scenarios;

    DinnerSubscriptionAction(List<DinnerSubscriptionScenario> scenarios) {
        this.scenarios = List.copyOf(scenarios);
    }

    public List<DinnerSubscriptionScenario> scenarios() {
        return scenarios;
    }
}
