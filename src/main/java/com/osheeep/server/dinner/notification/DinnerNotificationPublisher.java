package com.osheeep.server.dinner.notification;

public interface DinnerNotificationPublisher {

    void toPartner(
            Long householdId,
            Long actorUserId,
            DinnerNotificationType type,
            DinnerNotificationReferenceType referenceType,
            Long referenceId,
            Long referenceVersion,
            String sourceKey
    );

    void toRecipient(
            Long recipientUserId,
            Long householdId,
            DinnerNotificationType type,
            DinnerNotificationReferenceType referenceType,
            Long referenceId,
            Long referenceVersion,
            String sourceKey
    );

    static DinnerNotificationPublisher noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final DinnerNotificationPublisher INSTANCE =
                new DinnerNotificationPublisher() {
                    @Override
                    public void toPartner(
                            Long householdId,
                            Long actorUserId,
                            DinnerNotificationType type,
                            DinnerNotificationReferenceType referenceType,
                            Long referenceId,
                            Long referenceVersion,
                            String sourceKey
                    ) {}

                    @Override
                    public void toRecipient(
                            Long recipientUserId,
                            Long householdId,
                            DinnerNotificationType type,
                            DinnerNotificationReferenceType referenceType,
                            Long referenceId,
                            Long referenceVersion,
                            String sourceKey
                    ) {}
                };

        private NoopHolder() {}
    }
}
