package com.osheeep.server.dinner.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.notification.entity.DinnerNotificationEntity;
import com.osheeep.server.dinner.notification.mapper.DinnerNotificationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class PersistentDinnerNotificationPublisherTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-23T10:00:00Z"), ZoneOffset.UTC);

    private final DinnerHouseholdMemberMapper memberMapper =
            mock(DinnerHouseholdMemberMapper.class);
    private final DinnerNotificationMapper notificationMapper =
            mock(DinnerNotificationMapper.class);
    private final PersistentDinnerNotificationPublisher publisher =
            new PersistentDinnerNotificationPublisher(memberMapper, notificationMapper, CLOCK);

    @Test
    void writesOnePrivacySafePartnerNotificationWithDeterministicExpiryAndDedupe() {
        when(memberMapper.selectActiveByHouseholdId(11L))
                .thenReturn(List.of(member(71L, 7L), member(72L, 8L)));
        when(notificationMapper.insert(any(DinnerNotificationEntity.class))).thenReturn(1);

        publisher.toPartner(
                11L,
                7L,
                DinnerNotificationType.PARTNER_SELECTION_UPDATED,
                DinnerNotificationReferenceType.MENU,
                81L,
                5L,
                "menu:81:version:5");

        ArgumentCaptor<DinnerNotificationEntity> captor =
                ArgumentCaptor.forClass(DinnerNotificationEntity.class);
        verify(notificationMapper).insert(captor.capture());
        DinnerNotificationEntity persisted = captor.getValue();
        assertThat(persisted.getRecipientId()).isEqualTo(8L);
        assertThat(persisted.getHouseholdId()).isEqualTo(11L);
        assertThat(persisted.getType()).isEqualTo("PARTNER_SELECTION_UPDATED");
        assertThat(persisted.getReferenceType()).isEqualTo("MENU");
        assertThat(persisted.getReferenceId()).isEqualTo(81L);
        assertThat(persisted.getReferenceVersion()).isEqualTo(5L);
        assertThat(persisted.getDedupeKey()).matches("[0-9a-f]{64}");
        assertThat(persisted.getCreatedAt().toInstant(ZoneOffset.UTC))
                .isEqualTo(CLOCK.instant());
        assertThat(persisted.getExpiresAt()).isEqualTo(persisted.getCreatedAt().plusDays(90));
        assertThat(persisted.getReadAt()).isNull();
    }

    @Test
    void doesNothingWhenTheActorHasNoPartner() {
        when(memberMapper.selectActiveByHouseholdId(11L))
                .thenReturn(List.of(member(71L, 7L)));

        publisher.toPartner(
                11L,
                7L,
                DinnerNotificationType.INVENTORY_UPDATED,
                DinnerNotificationReferenceType.INVENTORY,
                91L,
                2L,
                "inventory:91:version:2");

        verify(notificationMapper, never()).insert(any(DinnerNotificationEntity.class));
    }

    @Test
    void treatsTheUniqueDedupeConflictAsAlreadyDelivered() {
        when(notificationMapper.insert(any(DinnerNotificationEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_dinner_notifications_dedupe"));

        publisher.toRecipient(
                8L,
                null,
                DinnerNotificationType.MEMBER_REMOVED,
                DinnerNotificationReferenceType.HOUSEHOLD_OPERATION,
                11L,
                8L,
                "operation:00000000-0000-4000-8000-000000000001");

        verify(notificationMapper).insert(any(DinnerNotificationEntity.class));
    }

    private DinnerHouseholdMemberEntity member(Long membershipId, Long userId) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(membershipId);
        member.setHouseholdId(11L);
        member.setUserId(userId);
        member.setRole(userId.equals(7L) ? "OWNER" : "MEMBER");
        member.setStatus("ACTIVE");
        member.setSeatNo(userId.equals(7L) ? 1 : 2);
        member.setVersion(1L);
        member.setHistoryVisibleFrom(
                CLOCK.instant().minusSeconds(60).atOffset(ZoneOffset.UTC).toLocalDateTime());
        return member;
    }
}
