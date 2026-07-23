package com.osheeep.server.auth.wechat;

import com.osheeep.server.dinner.subscription.WechatSubscriptionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    WechatProperties.class,
    WechatSubscriptionProperties.class
})
public class WechatConfig {
}
