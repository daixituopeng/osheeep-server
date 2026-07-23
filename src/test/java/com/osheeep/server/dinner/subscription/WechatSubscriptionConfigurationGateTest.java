package com.osheeep.server.dinner.subscription;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WechatSubscriptionConfigurationGateTest {

    @Test
    void disabledConfigurationNeedsNoProductionTemplates() {
        WechatSubscriptionProperties properties =
                new WechatSubscriptionProperties(false, null, null, null, null);

        assertThatCode(() ->
                new WechatSubscriptionConfigurationGate(properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void enabledConfigurationRequiresEveryDistinctTemplateAndField() {
        WechatSubscriptionProperties properties = validProperties();

        assertThatCode(() ->
                new WechatSubscriptionConfigurationGate(properties).afterPropertiesSet())
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> new WechatSubscriptionConfigurationGate(
                new WechatSubscriptionProperties(
                        true,
                        "formal",
                        properties.partnerJoined(),
                        template("partner-id", "菜单变化通知", "thing1", "time2", "thing3"),
                        properties.menuCompleted()))
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("template ids");

        assertThatThrownBy(() -> new WechatSubscriptionConfigurationGate(
                new WechatSubscriptionProperties(
                        true,
                        "formal",
                        properties.partnerJoined(),
                        template("changed-id", "TA 加入通知", "thing1", "time2", "thing3"),
                        properties.menuCompleted()))
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("template titles");

        assertThatThrownBy(() -> new WechatSubscriptionConfigurationGate(
                new WechatSubscriptionProperties(
                        true,
                        "formal",
                        properties.partnerJoined(),
                        template("changed-id", "菜单变化通知", "thing1", "thing1", "thing3"),
                        properties.menuCompleted()))
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("field keys");
    }

    @Test
    void enabledConfigurationRejectsPartialKeysAndUnsafeRuntimeState() {
        WechatSubscriptionProperties properties = validProperties();

        assertThatThrownBy(() -> new WechatSubscriptionConfigurationGate(
                new WechatSubscriptionProperties(
                        true,
                        "formal",
                        template("partner-id", "TA 加入通知", "thing1", "", "thing3"),
                        properties.menuChanged(),
                        properties.menuCompleted()))
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARTNER_JOINED");

        assertThatThrownBy(() -> new WechatSubscriptionConfigurationGate(
                new WechatSubscriptionProperties(
                        true,
                        "formal",
                        template(
                                " partner-id",
                                "TA 加入通知",
                                "thing1",
                                "time2",
                                "thing3"),
                        properties.menuChanged(),
                        properties.menuCompleted()))
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARTNER_JOINED");

        assertThatThrownBy(() -> new WechatSubscriptionConfigurationGate(
                new WechatSubscriptionProperties(
                        true,
                        "production",
                        properties.partnerJoined(),
                        properties.menuChanged(),
                        properties.menuCompleted()))
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("miniprogram-state");
    }

    private WechatSubscriptionProperties validProperties() {
        return new WechatSubscriptionProperties(
                true,
                "formal",
                template("partner-id", "TA 加入通知", "thing1", "time2", "thing3"),
                template("changed-id", "菜单变化通知", "thing4", "time5", "thing6"),
                template("completed-id", "晚饭完成通知", "thing7", "time8", "thing9"));
    }

    private WechatSubscriptionProperties.Template template(
            String id,
            String title,
            String subjectKey,
            String timeKey,
            String noteKey
    ) {
        return new WechatSubscriptionProperties.Template(
                id, title, subjectKey, timeKey, noteKey);
    }
}
