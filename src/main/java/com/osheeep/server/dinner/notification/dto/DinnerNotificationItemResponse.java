package com.osheeep.server.dinner.notification.dto;

import java.time.Instant;

public record DinnerNotificationItemResponse(
        Long id,
        String type,
        String title,
        String body,
        String target,
        boolean read,
        Instant createdAt
) {}
