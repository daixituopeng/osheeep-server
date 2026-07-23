package com.osheeep.server.dinner.notification;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.notification.dto.DinnerNotificationItemResponse;
import com.osheeep.server.dinner.notification.dto.DinnerNotificationPageResponse;
import com.osheeep.server.dinner.notification.entity.DinnerNotificationEntity;
import com.osheeep.server.dinner.notification.mapper.DinnerNotificationMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerNotificationService {

    private static final String ACTIVE = "ACTIVE";
    private static final int MAX_LIMIT = 50;

    private final UserMapper userMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerNotificationMapper notificationMapper;
    private final Clock clock;

    @Autowired
    public DinnerNotificationService(
            UserMapper userMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerNotificationMapper notificationMapper
    ) {
        this(userMapper, memberMapper, notificationMapper, Clock.systemUTC());
    }

    DinnerNotificationService(
            UserMapper userMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerNotificationMapper notificationMapper,
            Clock clock
    ) {
        this.userMapper = userMapper;
        this.memberMapper = memberMapper;
        this.notificationMapper = notificationMapper;
        this.clock = clock;
    }

    public DinnerNotificationPageResponse page(
            Long userId,
            Long beforeId,
            int limit
    ) {
        requireActiveUser(userId);
        if (beforeId != null && beforeId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Long householdId = currentHouseholdId(userId);
        LocalDateTime now = now();
        List<DinnerNotificationEntity> rows = notificationMapper.selectVisiblePage(
                userId, householdId, beforeId, now, limit + 1);
        if (rows == null) {
            throw new IllegalStateException("Notification page query returned null");
        }
        boolean hasMore = rows.size() > limit;
        List<DinnerNotificationEntity> visibleRows =
                hasMore ? rows.subList(0, limit) : rows;
        List<DinnerNotificationItemResponse> items =
                visibleRows.stream().map(this::toResponse).toList();
        Long nextBeforeId = hasMore && !items.isEmpty()
                ? items.getLast().id()
                : null;
        long unreadCount =
                notificationMapper.countVisibleUnread(userId, householdId, now);
        return new DinnerNotificationPageResponse(items, unreadCount, nextBeforeId);
    }

    public long unreadCount(Long userId) {
        requireActiveUser(userId);
        Long householdId = currentHouseholdId(userId);
        return notificationMapper.countVisibleUnread(userId, householdId, now());
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        requireActiveUser(userId);
        if (notificationId == null || notificationId <= 0) {
            throw new BusinessException(ErrorCode.DINNER_NOTIFICATION_NOT_FOUND);
        }
        Long householdId = currentHouseholdId(userId);
        LocalDateTime now = now();
        DinnerNotificationEntity notification =
                notificationMapper.selectVisibleByIdForUpdate(
                        notificationId, userId, householdId, now);
        if (notification == null
                || !Objects.equals(userId, notification.getRecipientId())) {
            throw new BusinessException(ErrorCode.DINNER_NOTIFICATION_NOT_FOUND);
        }
        if (notification.getReadAt() != null) {
            return;
        }
        if (notificationMapper.markRead(notificationId, userId, now) != 1) {
            throw new BusinessException(ErrorCode.DINNER_NOTIFICATION_NOT_FOUND);
        }
    }

    @Transactional
    public int markAllRead(Long userId) {
        requireActiveUser(userId);
        return notificationMapper.markAllVisibleRead(
                userId, currentHouseholdId(userId), now());
    }

    private DinnerNotificationItemResponse toResponse(DinnerNotificationEntity row) {
        if (row == null
                || row.getId() == null
                || row.getCreatedAt() == null
                || row.getExpiresAt() == null) {
            throw new IllegalStateException("Incomplete notification row");
        }
        DinnerNotificationType type =
                DinnerNotificationType.fromStoredValue(row.getType());
        return new DinnerNotificationItemResponse(
                row.getId(),
                type.name(),
                type.title(),
                type.body(),
                type.target().name(),
                row.getReadAt() != null,
                instant(row.getCreatedAt()));
    }

    private Long currentHouseholdId(Long userId) {
        DinnerHouseholdMemberEntity membership =
                memberMapper.selectActiveByUserId(userId);
        if (membership == null) {
            return null;
        }
        if (!Objects.equals(userId, membership.getUserId())
                || membership.getHouseholdId() == null
                || !ACTIVE.equals(membership.getStatus())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
        }
        return membership.getHouseholdId();
    }

    private void requireActiveUser(Long userId) {
        UserEntity user = userId == null ? null : userMapper.selectById(userId);
        if (user == null
                || !Objects.equals(userId, user.getId())
                || !ACTIVE.equals(user.getStatus())
                || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private Instant instant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
