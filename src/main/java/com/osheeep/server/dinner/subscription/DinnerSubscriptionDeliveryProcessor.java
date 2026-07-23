package com.osheeep.server.dinner.subscription;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.dinner.notification.DinnerNotificationType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DinnerSubscriptionDeliveryProcessor {

    private static final int MAX_ATTEMPTS = 5;
    private static final int INVALID_OPENID = 40003;
    private static final int INVALID_TEMPLATE_DATA = 47003;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter EVENT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DinnerSubscriptionDeliveryStore store;
    private final WechatUserIdentityMapper identityMapper;
    private final WechatSubscriptionMessageGateway gateway;
    private final WechatSubscriptionProperties properties;
    private final Clock clock;

    @Autowired
    public DinnerSubscriptionDeliveryProcessor(
            DinnerSubscriptionDeliveryStore store,
            WechatUserIdentityMapper identityMapper,
            WechatSubscriptionMessageGateway gateway,
            WechatSubscriptionProperties properties
    ) {
        this(store, identityMapper, gateway, properties, Clock.systemUTC());
    }

    DinnerSubscriptionDeliveryProcessor(
            DinnerSubscriptionDeliveryStore store,
            WechatUserIdentityMapper identityMapper,
            WechatSubscriptionMessageGateway gateway,
            WechatSubscriptionProperties properties,
            Clock clock
    ) {
        this.store = store;
        this.identityMapper = identityMapper;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean processNext() {
        if (!properties.enabled()) {
            return false;
        }
        LocalDateTime now = now();
        DinnerSubscriptionDeliveryClaim claim = store.claimNext(now);
        if (claim == null) {
            return false;
        }
        WechatUserIdentityEntity identity =
                identityMapper.selectByUserId(claim.recipientId());
        if (identity == null
                || !claim.recipientId().equals(identity.getUserId())
                || identity.getOpenid() == null
                || identity.getOpenid().isBlank()) {
            store.markTerminal(
                    claim.id(), claim.attemptCount(), INVALID_OPENID, now);
            return true;
        }
        WechatSubscriptionMessage message;
        try {
            message = message(claim, identity.getOpenid());
        } catch (RuntimeException exception) {
            store.markTerminal(
                    claim.id(), claim.attemptCount(), INVALID_TEMPLATE_DATA, now);
            return true;
        }
        try {
            WechatSubscriptionSendResult result = gateway.send(message);
            if (result.errorCode() == 0) {
                store.markSent(claim.id(), claim.attemptCount(), now);
            } else {
                finishFailure(claim, result.errorCode(), now);
            }
        } catch (WechatSubscriptionTransportException exception) {
            finishFailure(claim, null, now);
        }
        return true;
    }

    private WechatSubscriptionMessage message(
            DinnerSubscriptionDeliveryClaim claim,
            String openid
    ) {
        DinnerSubscriptionScenario scenario =
                DinnerSubscriptionScenario.valueOf(claim.scenario());
        DinnerNotificationType type =
                DinnerNotificationType.fromStoredValue(claim.notificationType());
        if (!scenario.accepts(type)) {
            throw new IllegalStateException("Subscription scenario does not match event");
        }
        WechatSubscriptionProperties.Template template =
                properties.template(scenario);
        Map<String, String> data = new LinkedHashMap<>();
        data.put(template.subjectKey(), truncate(type.title(), 20));
        data.put(template.timeKey(), formatEventTime(claim.eventAt()));
        data.put(template.noteKey(), truncate(type.body(), 20));
        return new WechatSubscriptionMessage(
                openid,
                template.id(),
                page(scenario),
                Map.copyOf(data),
                properties.miniprogramState());
    }

    private String page(DinnerSubscriptionScenario scenario) {
        return switch (scenario) {
            case PARTNER_JOINED -> "pages/household-manage/index";
            case MENU_CHANGED -> "pages/tonight/index";
            case MENU_COMPLETED -> "pages/records/index";
        };
    }

    private String formatEventTime(LocalDateTime eventAt) {
        return eventAt.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(SHANGHAI)
                .format(EVENT_TIME);
    }

    private String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }

    private void finishFailure(
            DinnerSubscriptionDeliveryClaim claim,
            Integer errorCode,
            LocalDateTime now
    ) {
        if (claim.attemptCount() >= MAX_ATTEMPTS || !retriable(errorCode)) {
            store.markTerminal(
                    claim.id(), claim.attemptCount(), errorCode, now);
            return;
        }
        store.markRetry(
                claim.id(),
                claim.attemptCount(),
                errorCode,
                now.plusMinutes(backoffMinutes(claim.attemptCount())),
                now);
    }

    private boolean retriable(Integer errorCode) {
        return errorCode == null
                || errorCode == -1
                || errorCode == 40001
                || errorCode == 40014
                || errorCode == 43108;
    }

    private long backoffMinutes(int attemptCount) {
        return switch (attemptCount) {
            case 1 -> 1L;
            case 2 -> 5L;
            case 3 -> 15L;
            default -> 60L;
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }
}
