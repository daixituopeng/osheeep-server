package com.osheeep.server.dinner.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionConfigResponse;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionResultItemRequest;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionResultRequest;
import com.osheeep.server.dinner.subscription.entity.DinnerSubscriptionDeliveryEntity;
import com.osheeep.server.dinner.subscription.mapper.DinnerSubscriptionDeliveryMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class DinnerSubscriptionServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-23T10:00:00Z"), ZoneOffset.UTC);

    private final DinnerHouseholdMemberMapper memberMapper =
            mock(DinnerHouseholdMemberMapper.class);
    private final DinnerSubscriptionDeliveryMapper deliveryMapper =
            mock(DinnerSubscriptionDeliveryMapper.class);

    @Test
    void disabledConfigurationReturnsNoTemplateIdsWithoutReadingMembership() {
        DinnerSubscriptionService service = new DinnerSubscriptionService(
                memberMapper,
                deliveryMapper,
                new WechatSubscriptionProperties(false, null, null, null, null),
                CLOCK);

        assertThat(service.config(7L))
                .isEqualTo(new DinnerSubscriptionConfigResponse(List.of()));
        verify(memberMapper, never()).selectActiveByUserId(any());
        verify(deliveryMapper, never()).selectBlockingScenarios(any(), any(), any());
    }

    @Test
    void exposesOnlyConfiguredAndCurrentlyPromptableActions() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 11L));
        when(deliveryMapper.selectBlockingScenarios(
                7L, 11L, CLOCK.instant().atOffset(ZoneOffset.UTC).toLocalDateTime()))
                .thenReturn(List.of("MENU_CHANGED"));
        DinnerSubscriptionService service = service();

        DinnerSubscriptionConfigResponse response = service.config(7L);

        assertThat(response.actions()).hasSize(2);
        assertThat(response.actions().getFirst().action())
                .isEqualTo(DinnerSubscriptionAction.HOUSEHOLD_INVITE_READY.name());
        assertThat(response.actions().getFirst().templates())
                .singleElement()
                .satisfies(template -> {
                    assertThat(template.scenario()).isEqualTo("PARTNER_JOINED");
                    assertThat(template.templateId()).isEqualTo("partner-id");
                });
        assertThat(response.actions().get(1).action())
                .isEqualTo(DinnerSubscriptionAction.MENU_CONFIRMED.name());
        assertThat(response.actions().get(1).templates())
                .singleElement()
                .satisfies(template -> {
                    assertThat(template.scenario()).isEqualTo("MENU_COMPLETED");
                    assertThat(template.templateId()).isEqualTo("completed-id");
                });
    }

    @Test
    void recordsAcceptedAndRejectedResultsWithoutPersistingTemplateIds() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 11L));
        when(deliveryMapper.insert(any(DinnerSubscriptionDeliveryEntity.class)))
                .thenReturn(1);
        DinnerSubscriptionService service = service();
        UUID requestId = UUID.fromString("00000000-0000-4000-8000-000000000016");

        service.recordResults(
                7L,
                new DinnerSubscriptionResultRequest(
                        requestId,
                        "MENU_CONFIRMED",
                        List.of(
                                new DinnerSubscriptionResultItemRequest(
                                        "MENU_CHANGED", "ACCEPT"),
                                new DinnerSubscriptionResultItemRequest(
                                        "MENU_COMPLETED", "REJECT"))));

        ArgumentCaptor<DinnerSubscriptionDeliveryEntity> captor =
                ArgumentCaptor.forClass(DinnerSubscriptionDeliveryEntity.class);
        verify(deliveryMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(
                        DinnerSubscriptionDeliveryEntity::getScenario,
                        DinnerSubscriptionDeliveryEntity::getOutcome,
                        DinnerSubscriptionDeliveryEntity::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "MENU_CHANGED", "ACCEPT", "WAITING_EVENT"),
                        org.assertj.core.groups.Tuple.tuple(
                                "MENU_COMPLETED", "REJECT", "REJECTED"));
        assertThat(captor.getAllValues())
                .allSatisfy(row -> {
                    assertThat(row.getRecipientId()).isEqualTo(7L);
                    assertThat(row.getHouseholdId()).isEqualTo(11L);
                    assertThat(row.getRequestKey()).isEqualTo(requestId.toString());
                    assertThat(row.getCreatedAt().toInstant(ZoneOffset.UTC))
                            .isEqualTo(CLOCK.instant());
                    assertThat(row.getExpiresAt())
                            .isEqualTo(row.getCreatedAt().plusDays(90));
                    assertThat(row.toString())
                            .doesNotContain("partner-id", "changed-id", "completed-id");
                });
    }

    @Test
    void rejectsScenariosOutsideTheSuccessfulActionBeforeWriting() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 11L));
        DinnerSubscriptionService service = service();

        assertThatThrownBy(() -> service.recordResults(
                7L,
                new DinnerSubscriptionResultRequest(
                        UUID.randomUUID(),
                        "HOUSEHOLD_INVITE_READY",
                        List.of(new DinnerSubscriptionResultItemRequest(
                                "MENU_COMPLETED", "ACCEPT")))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(deliveryMapper, never())
                .insert(any(DinnerSubscriptionDeliveryEntity.class));
    }

    @Test
    void rejectsARequestIdThatIsNotUuidV4BeforeWriting() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 11L));
        DinnerSubscriptionService service = service();

        assertThatThrownBy(() -> service.recordResults(
                7L,
                new DinnerSubscriptionResultRequest(
                        UUID.fromString("00000000-0000-1000-8000-000000000016"),
                        "HOUSEHOLD_INVITE_READY",
                        List.of(new DinnerSubscriptionResultItemRequest(
                                "PARTNER_JOINED", "ACCEPT")))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(deliveryMapper, never())
                .insert(any(DinnerSubscriptionDeliveryEntity.class));
    }

    @Test
    void treatsARepeatedResultRequestAsAlreadyRecorded() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 11L));
        when(deliveryMapper.insert(any(DinnerSubscriptionDeliveryEntity.class)))
                .thenThrow(new DuplicateKeyException("request key"));
        DinnerSubscriptionService service = service();

        service.recordResults(
                7L,
                new DinnerSubscriptionResultRequest(
                        UUID.randomUUID(),
                        "HOUSEHOLD_INVITE_READY",
                        List.of(new DinnerSubscriptionResultItemRequest(
                                "PARTNER_JOINED", "ACCEPT"))));

        verify(deliveryMapper).insert(any(DinnerSubscriptionDeliveryEntity.class));
    }

    private DinnerSubscriptionService service() {
        return new DinnerSubscriptionService(
                memberMapper, deliveryMapper, properties(), CLOCK);
    }

    private WechatSubscriptionProperties properties() {
        return new WechatSubscriptionProperties(
                true,
                "formal",
                template("partner-id", "TA 加入通知", "thing1", "time2", "thing3"),
                template("changed-id", "菜单变化通知", "thing4", "time5", "thing6"),
                template("completed-id", "晚饭完成通知", "thing7", "time8", "thing9"));
    }

    private WechatSubscriptionProperties.Template template(
            String id,
            String title,
            String subjectKey,
            String timeKey,
            String noteKey
    ) {
        return new WechatSubscriptionProperties.Template(
                id, title, subjectKey, timeKey, noteKey);
    }

    private DinnerHouseholdMemberEntity member(Long userId, Long householdId) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(71L);
        member.setUserId(userId);
        member.setHouseholdId(householdId);
        member.setRole("OWNER");
        member.setStatus("ACTIVE");
        member.setSeatNo(1);
        member.setVersion(1L);
        return member;
    }
}
