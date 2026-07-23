package com.osheeep.server.dinner.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.notification.dto.DinnerNotificationPageResponse;
import com.osheeep.server.dinner.notification.entity.DinnerNotificationEntity;
import com.osheeep.server.dinner.notification.mapper.DinnerNotificationMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DinnerNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    private final UserMapper userMapper = mock(UserMapper.class);
    private final DinnerHouseholdMemberMapper memberMapper =
            mock(DinnerHouseholdMemberMapper.class);
    private final DinnerNotificationMapper notificationMapper =
            mock(DinnerNotificationMapper.class);
    private final DinnerNotificationService service = new DinnerNotificationService(
            userMapper,
            memberMapper,
            notificationMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void activeUser() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus("ACTIVE");
        when(userMapper.selectById(7L)).thenReturn(user);

        DinnerHouseholdMemberEntity membership = new DinnerHouseholdMemberEntity();
        membership.setId(71L);
        membership.setUserId(7L);
        membership.setHouseholdId(11L);
        membership.setStatus("ACTIVE");
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(membership);
    }

    @Test
    void listsOnlyCurrentScopeWithCursorAndControlledPresentation() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        DinnerNotificationEntity first = notification(
                105L, "PARTNER_SELECTION_UPDATED", false, now.minusMinutes(1));
        DinnerNotificationEntity second = notification(
                104L, "MENU_COMPLETED", true, now.minusMinutes(2));
        DinnerNotificationEntity sentinel = notification(
                103L, "INVENTORY_UPDATED", false, now.minusMinutes(3));
        when(notificationMapper.selectVisiblePage(7L, 11L, null, now, 3))
                .thenReturn(List.of(first, second, sentinel));
        when(notificationMapper.countVisibleUnread(7L, 11L, now)).thenReturn(2L);

        DinnerNotificationPageResponse page = service.page(7L, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getFirst().id()).isEqualTo(105L);
        assertThat(page.items().getFirst().title()).isEqualTo("TA 更新了今晚选择");
        assertThat(page.items().getFirst().target()).isEqualTo("TONIGHT");
        assertThat(page.items().getFirst().read()).isFalse();
        assertThat(page.items().get(1).target()).isEqualTo("RECORDS");
        assertThat(page.unreadCount()).isEqualTo(2L);
        assertThat(page.nextBeforeId()).isEqualTo(104L);
    }

    @Test
    void usersWithoutAHouseholdCanStillReadPersonalNotifications() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(null);
        when(notificationMapper.selectVisiblePage(7L, null, null, now, 21))
                .thenReturn(List.of(notification(
                        9L, "MEMBER_REMOVED", false, now.minusMinutes(1))));
        when(notificationMapper.countVisibleUnread(7L, null, now)).thenReturn(1L);

        DinnerNotificationPageResponse page = service.page(7L, null, 20);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("你已离开原小家");
            assertThat(item.target()).isEqualTo("HOUSEHOLD_BINDING");
        });
    }

    @Test
    void marksOneVisibleNotificationReadIdempotently() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        DinnerNotificationEntity notification =
                notification(105L, "PARTNER_JOINED", false, now.minusMinutes(1));
        when(notificationMapper.selectVisibleByIdForUpdate(105L, 7L, 11L, now))
                .thenReturn(notification);
        when(notificationMapper.markRead(105L, 7L, now)).thenReturn(1);

        service.markRead(7L, 105L);

        verify(notificationMapper).markRead(105L, 7L, now);

        notification.setReadAt(now.minusSeconds(1));
        service.markRead(7L, 105L);

        verify(notificationMapper).markRead(105L, 7L, now);
    }

    @Test
    void hidesExistenceOfOtherExpiredOrOldHouseholdNotifications() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(notificationMapper.selectVisibleByIdForUpdate(999L, 7L, 11L, now))
                .thenReturn(null);

        assertThatThrownBy(() -> service.markRead(7L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.DINNER_NOTIFICATION_NOT_FOUND);

        verify(notificationMapper, never()).markRead(999L, 7L, now);
    }

    @Test
    void marksAllCurrentScopeNotificationsWithoutTouchingOldHouseholds() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(notificationMapper.markAllVisibleRead(7L, 11L, now)).thenReturn(3);

        int updated = service.markAllRead(7L);

        assertThat(updated).isEqualTo(3);
        verify(notificationMapper).markAllVisibleRead(7L, 11L, now);
    }

    @Test
    void rejectsDeletedUsersBeforeReadingNotificationState() {
        UserEntity deleted = new UserEntity();
        deleted.setId(7L);
        deleted.setStatus("DELETED");
        deleted.setDeletedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(userMapper.selectById(7L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.unreadCount(7L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private DinnerNotificationEntity notification(
            long id,
            String type,
            boolean read,
            LocalDateTime createdAt
    ) {
        DinnerNotificationEntity entity = new DinnerNotificationEntity();
        entity.setId(id);
        entity.setRecipientId(7L);
        entity.setHouseholdId("MEMBER_REMOVED".equals(type) ? null : 11L);
        entity.setType(type);
        entity.setReferenceType("MENU");
        entity.setReferenceId(81L);
        entity.setReferenceVersion(4L);
        entity.setDedupeKey("a".repeat(64));
        entity.setCreatedAt(createdAt);
        entity.setExpiresAt(createdAt.plusDays(90));
        entity.setReadAt(read ? createdAt.plusMinutes(1) : null);
        return entity;
    }
}
