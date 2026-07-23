package com.osheeep.server.dinner.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.dinner.notification.mapper.DinnerNotificationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DinnerNotificationRetentionServiceTest {

    @Test
    void deletesRowsAtTheExactUtcRetentionBoundary() {
        Instant now = Instant.parse("2026-10-21T10:00:00.123456Z");
        LocalDateTime expected = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
                .withNano(123_000_000);
        DinnerNotificationMapper mapper = mock(DinnerNotificationMapper.class);
        when(mapper.deleteExpired(expected)).thenReturn(12);
        DinnerNotificationRetentionService service =
                new DinnerNotificationRetentionService(
                        mapper,
                        Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.deleteExpired()).isEqualTo(12);
        verify(mapper).deleteExpired(expected);
    }
}
