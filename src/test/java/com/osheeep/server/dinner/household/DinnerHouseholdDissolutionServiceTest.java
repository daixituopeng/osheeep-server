package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.auth.wechat.WechatCode2SessionClient;
import com.osheeep.server.auth.wechat.WechatSession;
import com.osheeep.server.dinner.household.DinnerHouseholdOperationService.HouseholdOperationCommand;
import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdDissolutionServiceTest {

    private static final String KEY = "7b20fb9b-a868-48bf-98e5-36643b9921b1";
    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Mock private WechatCode2SessionClient sessionClient;
    @Mock private DinnerHouseholdOperationMapper operationMapper;
    @Mock private DinnerHouseholdOperationRetentionService retentionService;
    @Mock private DinnerHouseholdNameService nameService;
    @Mock private DinnerHouseholdDissolutionTransaction dissolutionTransaction;

    private DinnerHouseholdDissolutionService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdDissolutionService(
                sessionClient,
                operationMapper,
                new HouseholdOperationFingerprinter(
                        "test-secret-at-least-32-characters"),
                retentionService,
                nameService,
                dissolutionTransaction,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void freshRequestNormalizesNameThenExchangesCodeOutsideTransaction() {
        when(nameService.normalize(" 我们的小家 ")).thenReturn("我们的小家");
        when(operationMapper.selectByActorAndIdempotencyKey(7L, KEY)).thenReturn(null);
        when(sessionClient.exchange("fresh-code"))
                .thenReturn(new WechatSession("openid-7"));
        HouseholdMutationResponse response = new HouseholdMutationResponse(
                "HOUSEHOLD_DISSOLUTION", false, false, null);
        when(dissolutionTransaction.dissolve(
                any(HouseholdOperationCommand.class),
                org.mockito.ArgumentMatchers.eq("我们的小家"),
                org.mockito.ArgumentMatchers.eq("openid-7"))).thenReturn(response);

        assertThat(service.dissolve(
                7L, 31L, 8L, " 我们的小家 ", "fresh-code", KEY))
                .isSameAs(response);

        ArgumentCaptor<HouseholdOperationCommand> command =
                ArgumentCaptor.forClass(HouseholdOperationCommand.class);
        verify(dissolutionTransaction).dissolve(
                command.capture(),
                org.mockito.ArgumentMatchers.eq("我们的小家"),
                org.mockito.ArgumentMatchers.eq("openid-7"));
        assertThat(command.getValue().operationType()).isEqualTo("HOUSEHOLD_DISSOLUTION");
        assertThat(command.getValue().targetMembershipId()).isNull();
        assertThat(command.getValue().fingerprint()).isEqualTo(
                new HouseholdOperationFingerprinter(
                        "test-secret-at-least-32-characters")
                        .fingerprint(
                                "HOUSEHOLD_DISSOLUTION",
                                31L,
                                8L,
                                null,
                                null,
                                "我们的小家"));
        assertThat(command.getValue().fingerprint()).doesNotContain("fresh-code", "我们的小家");
    }

    @Test
    void committedReplayAfterHouseholdDeletionDoesNotExchangeAnotherCode() {
        when(nameService.normalize("我们的小家")).thenReturn("我们的小家");
        DinnerHouseholdOperationEntity existing = operationResult();
        when(operationMapper.selectByActorAndIdempotencyKey(7L, KEY))
                .thenReturn(existing);

        HouseholdMutationResponse result = service.dissolve(
                7L, 31L, 8L, "我们的小家", "another-code", KEY);

        assertThat(result.operationType()).isEqualTo("HOUSEHOLD_DISSOLUTION");
        assertThat(result.replayed()).isTrue();
        assertThat(result.actorHasHousehold()).isFalse();
        assertThat(result.householdVersion()).isNull();
        verifyNoInteractions(sessionClient, dissolutionTransaction);
    }

    @Test
    void failedWechatExchangeNeverStartsDissolutionTransaction() {
        when(nameService.normalize("我们的小家")).thenReturn("我们的小家");
        when(operationMapper.selectByActorAndIdempotencyKey(7L, KEY)).thenReturn(null);
        when(sessionClient.exchange("used-code")).thenThrow(new IllegalStateException("used"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.dissolve(
                7L, 31L, 8L, "我们的小家", "used-code", KEY))
                .isInstanceOf(IllegalStateException.class);

        verify(dissolutionTransaction, never()).dissolve(any(), any(), any());
    }

    private DinnerHouseholdOperationEntity operationResult() {
        HouseholdOperationFingerprinter fingerprinter =
                new HouseholdOperationFingerprinter(
                        "test-secret-at-least-32-characters");
        DinnerHouseholdOperationEntity operation = new DinnerHouseholdOperationEntity();
        operation.setHouseholdId(11L);
        operation.setActorId(7L);
        operation.setActorMembershipId(31L);
        operation.setOperationType("HOUSEHOLD_DISSOLUTION");
        operation.setIdempotencyKey(KEY);
        operation.setRequestFingerprint(fingerprinter.fingerprint(
                "HOUSEHOLD_DISSOLUTION", 31L, 8L, null, null, "我们的小家"));
        operation.setResultSchemaVersion(1);
        operation.setResultHouseholdVersion(null);
        operation.setResultPayload("{\"actorHasHousehold\":false}");
        operation.setCreatedAt(LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        operation.setExpiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        return operation;
    }
}
