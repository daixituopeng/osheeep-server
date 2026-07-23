package com.osheeep.server.dinner.subscription.dto;

import java.util.List;

public record DinnerSubscriptionActionResponse(
        String action,
        List<DinnerSubscriptionTemplateResponse> templates
) {
}
