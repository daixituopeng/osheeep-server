package com.osheeep.server.dinner.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.dinner.subscription.mapper.DinnerSubscriptionDeliveryMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DinnerSubscriptionRetentionServiceTest {

    @Test
    void deletesRowsAtTheExactNinetyDayExpiryBoundary() {
        DinnerSubscriptionDeliveryMapper mapper =
                mock(DinnerSubscriptionDeliveryMapper.class);
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.parse("2026-10-21T10:00:00");
        when(mapper.deleteExpired(now)).thenReturn(3);
        DinnerSubscriptionRetentionService service =
                new DinnerSubscriptionRetentionService(mapper, clock);

        assertThat(service.deleteExpired()).isEqualTo(3);
        verify(mapper).deleteExpired(now);
    }
}
