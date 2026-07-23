package com.osheeep.server.dinner.subscription;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "osheeep.wechat.subscription")
public record WechatSubscriptionProperties(
        boolean enabled,
        String miniprogramState,
        Template partnerJoined,
        Template menuChanged,
        Template menuCompleted
) {

    public record Template(
            String id,
            String title,
            String subjectKey,
            String timeKey,
            String noteKey
    ) {
    }

    public Template template(DinnerSubscriptionScenario scenario) {
        return switch (scenario) {
            case PARTNER_JOINED -> partnerJoined;
            case MENU_CHANGED -> menuChanged;
            case MENU_COMPLETED -> menuCompleted;
        };
    }
}
