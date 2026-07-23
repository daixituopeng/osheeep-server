package com.osheeep.server.dinner.notification;

public record DinnerNotificationCommittedEvent(
        Long recipientUserId,
        Long householdId,
        DinnerNotificationType type,
        DinnerNotificationReferenceType referenceType,
        Long referenceId,
        Long referenceVersion,
        String eventDedupeKey
) {
}
