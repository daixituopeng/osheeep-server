package com.osheeep.server.dinner.subscription.dto;

import java.util.List;

public record DinnerSubscriptionConfigResponse(
        List<DinnerSubscriptionActionResponse> actions
) {
}
