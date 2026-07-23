package com.osheeep.server.dinner.notification.dto;

import java.util.List;

public record DinnerNotificationPageResponse(
        List<DinnerNotificationItemResponse> items,
        long unreadCount,
        Long nextBeforeId
) {
    public DinnerNotificationPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
