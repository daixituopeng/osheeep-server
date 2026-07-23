package com.osheeep.server.dinner.notification;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.notification.entity.DinnerNotificationEntity;
import com.osheeep.server.dinner.notification.mapper.DinnerNotificationMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PersistentDinnerNotificationPublisher implements DinnerNotificationPublisher {

    private static final String ACTIVE = "ACTIVE";
    private static final long RETENTION_DAYS = 90L;

    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerNotificationMapper notificationMapper;
    private final Clock clock;

    @Autowired
    public PersistentDinnerNotificationPublisher(
            DinnerHouseholdMemberMapper memberMapper,
            DinnerNotificationMapper notificationMapper
    ) {
        this(memberMapper, notificationMapper, Clock.systemUTC());
    }

    PersistentDinnerNotificationPublisher(
            DinnerHouseholdMemberMapper memberMapper,
            DinnerNotificationMapper notificationMapper,
            Clock clock
    ) {
        this.memberMapper = memberMapper;
        this.notificationMapper = notificationMapper;
        this.clock = clock;
    }

    @Override
    public void toPartner(
            Long householdId,
            Long actorUserId,
            DinnerNotificationType type,
            DinnerNotificationReferenceType referenceType,
            Long referenceId,
            Long referenceVersion,
            String sourceKey
    ) {
        requirePositive(householdId, "Household id");
        requirePositive(actorUserId, "Actor user id");
        List<DinnerHouseholdMemberEntity> members =
                memberMapper.selectActiveByHouseholdId(householdId);
        if (members == null || members.isEmpty() || members.size() > 2) {
            throw stateConflict();
        }
        Long previousId = null;
        Long recipientUserId = null;
        boolean actorFound = false;
        for (DinnerHouseholdMemberEntity member : members) {
            if (!validActiveMember(member, householdId)
                    || previousId != null && member.getId() <= previousId) {
                throw stateConflict();
            }
            if (Objects.equals(actorUserId, member.getUserId())) {
                if (actorFound) {
                    throw stateConflict();
                }
                actorFound = true;
            } else if (recipientUserId != null) {
                throw stateConflict();
            } else {
                recipientUserId = member.getUserId();
            }
            previousId = member.getId();
        }
        if (!actorFound) {
            throw stateConflict();
        }
        if (recipientUserId == null) {
            return;
        }
        toRecipient(
                recipientUserId,
                householdId,
                type,
                referenceType,
                referenceId,
                referenceVersion,
                sourceKey);
    }

    @Override
    public void toRecipient(
            Long recipientUserId,
            Long householdId,
            DinnerNotificationType type,
            DinnerNotificationReferenceType referenceType,
            Long referenceId,
            Long referenceVersion,
            String sourceKey
    ) {
        requirePositive(recipientUserId, "Recipient user id");
        if (householdId != null) {
            requirePositive(householdId, "Household id");
        }
        Objects.requireNonNull(type, "Notification type is required");
        Objects.requireNonNull(referenceType, "Reference type is required");
        requirePositive(referenceId, "Reference id");
        if (referenceVersion != null && referenceVersion < 0) {
            throw new IllegalArgumentException("Reference version must not be negative");
        }
        if (!StringUtils.hasText(sourceKey)) {
            throw new IllegalArgumentException("Notification source key is required");
        }
        if (type == DinnerNotificationType.MEMBER_REMOVED && householdId != null
                || type != DinnerNotificationType.MEMBER_REMOVED && householdId == null) {
            throw new IllegalArgumentException("Notification scope does not match its type");
        }

        LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
        DinnerNotificationEntity notification = new DinnerNotificationEntity();
        notification.setRecipientId(recipientUserId);
        notification.setHouseholdId(householdId);
        notification.setType(type.name());
        notification.setReferenceType(referenceType.name());
        notification.setReferenceId(referenceId);
        notification.setReferenceVersion(referenceVersion);
        notification.setDedupeKey(dedupeKey(recipientUserId, type, sourceKey));
        notification.setCreatedAt(createdAt);
        notification.setExpiresAt(createdAt.plusDays(RETENTION_DAYS));
        try {
            if (notificationMapper.insert(notification) != 1) {
                throw new IllegalStateException("Notification was not stored");
            }
        } catch (DuplicateKeyException ignored) {
            // The same committed domain event was already delivered.
        }
    }

    private boolean validActiveMember(
            DinnerHouseholdMemberEntity member,
            Long householdId
    ) {
        return member != null
                && member.getId() != null
                && Objects.equals(householdId, member.getHouseholdId())
                && member.getUserId() != null
                && ACTIVE.equals(member.getStatus());
    }

    private String dedupeKey(
            Long recipientUserId,
            DinnerNotificationType type,
            String sourceKey
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    (recipientUserId + "|" + type.name() + "|" + sourceKey)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requirePositive(Long value, String label) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private BusinessException stateConflict() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
    }
}
