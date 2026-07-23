package com.osheeep.server.dinner.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.dinner.subscription.entity.DinnerSubscriptionDeliveryEntity;
import com.osheeep.server.dinner.subscription.mapper.DinnerSubscriptionDeliveryMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DinnerSubscriptionDeliveryStoreTest {

    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-07-23T10:00:00");

    private final DinnerSubscriptionDeliveryMapper mapper =
            mock(DinnerSubscriptionDeliveryMapper.class);
    private final DinnerSubscriptionDeliveryStore store =
            new DinnerSubscriptionDeliveryStore(mapper);

    @Test
    void claimsReadyDeliveryAndAdvancesTheAttemptExactlyOnce() {
        DinnerSubscriptionDeliveryEntity row = row("READY", 2);
        when(mapper.selectNextClaimableForUpdate(NOW, NOW.minusMinutes(5)))
                .thenReturn(row);
        when(mapper.markSending(501L, "READY", 2, 3, NOW)).thenReturn(1);

        DinnerSubscriptionDeliveryClaim claim = store.claimNext(NOW);

        assertThat(claim.attemptCount()).isEqualTo(3);
        verify(mapper).markSending(501L, "READY", 2, 3, NOW);
    }

    @Test
    void terminatesAStaleFifthAttemptWithoutCreatingAnInvalidSixthAttempt() {
        DinnerSubscriptionDeliveryEntity row = row("SENDING", 5);
        when(mapper.selectNextClaimableForUpdate(NOW, NOW.minusMinutes(5)))
                .thenReturn(row);
        when(mapper.markTerminal(501L, 5, null, NOW)).thenReturn(1);

        assertThat(store.claimNext(NOW)).isNull();

        verify(mapper).markTerminal(501L, 5, null, NOW);
        verify(mapper, never()).markSending(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    private DinnerSubscriptionDeliveryEntity row(String status, int attempts) {
        DinnerSubscriptionDeliveryEntity row =
                new DinnerSubscriptionDeliveryEntity();
        row.setId(501L);
        row.setRecipientId(8L);
        row.setScenario("MENU_CHANGED");
        row.setNotificationType("MENU_RECONFIRM_REQUIRED");
        row.setStatus(status);
        row.setAttemptCount(attempts);
        row.setUpdatedAt(LocalDateTime.parse("2026-07-23T09:50:00"));
        return row;
    }
}
