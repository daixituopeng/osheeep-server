package com.osheeep.server.dinner.notification;

import com.osheeep.server.dinner.notification.mapper.DinnerNotificationMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerNotificationRetentionService {

    private final DinnerNotificationMapper mapper;
    private final Clock clock;

    @Autowired
    public DinnerNotificationRetentionService(DinnerNotificationMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    DinnerNotificationRetentionService(
            DinnerNotificationMapper mapper,
            Clock clock
    ) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public int deleteExpired() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
        return mapper.deleteExpired(now);
    }
}
