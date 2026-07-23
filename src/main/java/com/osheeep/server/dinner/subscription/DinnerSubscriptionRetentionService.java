package com.osheeep.server.dinner.subscription;

import com.osheeep.server.dinner.subscription.mapper.DinnerSubscriptionDeliveryMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerSubscriptionRetentionService {

    private final DinnerSubscriptionDeliveryMapper mapper;
    private final Clock clock;

    @Autowired
    public DinnerSubscriptionRetentionService(
            DinnerSubscriptionDeliveryMapper mapper
    ) {
        this(mapper, Clock.systemUTC());
    }

    DinnerSubscriptionRetentionService(
            DinnerSubscriptionDeliveryMapper mapper,
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
