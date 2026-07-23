package com.osheeep.server.dinner.subscription;

import java.util.Map;

public record WechatSubscriptionMessage(
        String openid,
        String templateId,
        String page,
        Map<String, String> data,
        String miniprogramState
) {
}
