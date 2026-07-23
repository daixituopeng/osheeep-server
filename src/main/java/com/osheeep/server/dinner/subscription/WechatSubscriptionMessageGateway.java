package com.osheeep.server.dinner.subscription;

public interface WechatSubscriptionMessageGateway {

    WechatSubscriptionSendResult send(WechatSubscriptionMessage message);
}
