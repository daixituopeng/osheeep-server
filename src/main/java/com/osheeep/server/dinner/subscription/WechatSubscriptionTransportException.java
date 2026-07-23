package com.osheeep.server.dinner.subscription;

public class WechatSubscriptionTransportException extends RuntimeException {

    public WechatSubscriptionTransportException() {
        super("WeChat subscription delivery is temporarily unavailable");
    }
}
