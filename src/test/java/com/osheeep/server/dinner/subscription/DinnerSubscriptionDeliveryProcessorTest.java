package com.osheeep.server.dinner.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DinnerSubscriptionDeliveryProcessorTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-23T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-07-23T10:00:00");

    private final DinnerSubscriptionDeliveryStore store =
            mock(DinnerSubscriptionDeliveryStore.class);
    private final WechatUserIdentityMapper identityMapper =
            mock(WechatUserIdentityMapper.class);
    private final WechatSubscriptionMessageGateway gateway =
            mock(WechatSubscriptionMessageGateway.class);

    @Test
    void sendsAClaimWithControlledTemplateFieldsAndMarksItSent() {
        when(store.claimNext(NOW)).thenReturn(claim(1));
        when(identityMapper.selectByUserId(8L)).thenReturn(identity());
        when(gateway.send(any())).thenReturn(new WechatSubscriptionSendResult(0));
        DinnerSubscriptionDeliveryProcessor processor = processor();

        assertThat(processor.processNext()).isTrue();

        ArgumentCaptor<WechatSubscriptionMessage> captor =
                ArgumentCaptor.forClass(WechatSubscriptionMessage.class);
        verify(gateway).send(captor.capture());
        assertThat(captor.getValue()).satisfies(message -> {
            assertThat(message.openid()).isEqualTo("openid-8");
            assertThat(message.templateId()).isEqualTo("changed-id");
            assertThat(message.page()).isEqualTo("pages/tonight/index");
            assertThat(message.miniprogramState()).isEqualTo("formal");
            assertThat(message.data()).containsOnlyKeys("thing4", "time5", "thing6");
            assertThat(message.data().get("thing4")).hasSizeLessThanOrEqualTo(20);
            assertThat(message.data().get("thing6")).hasSizeLessThanOrEqualTo(20);
        });
        verify(store).markSent(501L, 1, NOW);
    }

    @Test
    void terminalWechatResultNeverRetriesOrTouchesTheBusinessEvent() {
        when(store.claimNext(NOW)).thenReturn(claim(1));
        when(identityMapper.selectByUserId(8L)).thenReturn(identity());
        when(gateway.send(any())).thenReturn(
                new WechatSubscriptionSendResult(43101));
        DinnerSubscriptionDeliveryProcessor processor = processor();

        assertThat(processor.processNext()).isTrue();

        verify(store).markTerminal(501L, 1, 43101, NOW);
        verify(store, never()).markRetry(
                any(), any(Integer.class), any(), any(), any());
    }

    @Test
    void transportFailureRetriesWithABoundedBackoffThenTerminates() {
        when(store.claimNext(NOW))
                .thenReturn(claim(1))
                .thenReturn(claim(5));
        when(identityMapper.selectByUserId(8L)).thenReturn(identity());
        when(gateway.send(any())).thenThrow(
                new WechatSubscriptionTransportException());
        DinnerSubscriptionDeliveryProcessor processor = processor();

        assertThat(processor.processNext()).isTrue();
        verify(store).markRetry(
                501L, 1, null, NOW.plusMinutes(1), NOW);

        assertThat(processor.processNext()).isTrue();
        verify(store).markTerminal(501L, 5, null, NOW);
    }

    @Test
    void disabledConfigurationDoesNotClaimOrSend() {
        DinnerSubscriptionDeliveryProcessor processor =
                new DinnerSubscriptionDeliveryProcessor(
                        store,
                        identityMapper,
                        gateway,
                        new WechatSubscriptionProperties(
                                false, null, null, null, null),
                        CLOCK);

        assertThat(processor.processNext()).isFalse();
        verify(store, never()).claimNext(any());
        verify(gateway, never()).send(any());
    }

    private DinnerSubscriptionDeliveryProcessor processor() {
        return new DinnerSubscriptionDeliveryProcessor(
                store, identityMapper, gateway, properties(), CLOCK);
    }

    private DinnerSubscriptionDeliveryClaim claim(int attempt) {
        return new DinnerSubscriptionDeliveryClaim(
                501L,
                8L,
                "MENU_CHANGED",
                "MENU_RECONFIRM_REQUIRED",
                attempt,
                LocalDateTime.parse("2026-07-23T09:58:00"));
    }

    private WechatUserIdentityEntity identity() {
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setId(88L);
        identity.setUserId(8L);
        identity.setOpenid("openid-8");
        return identity;
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
}
