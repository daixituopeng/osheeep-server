package com.osheeep.server.dinner.subscription;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class WechatSubscriptionConfigurationGate implements InitializingBean {

    private static final Set<String> STATES =
            Set.of("developer", "trial", "formal");
    private static final Pattern FIELD_KEY =
            Pattern.compile("[A-Za-z]+[0-9]+");

    private final WechatSubscriptionProperties properties;

    public WechatSubscriptionConfigurationGate(
            WechatSubscriptionProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.enabled()) {
            return;
        }
        if (!STATES.contains(properties.miniprogramState())) {
            throw invalid("miniprogram-state must be developer, trial or formal");
        }
        List<WechatSubscriptionProperties.Template> templates =
                Arrays.stream(DinnerSubscriptionScenario.values())
                        .map(scenario -> requireComplete(
                                scenario, properties.template(scenario)))
                        .toList();
        requireDistinct(
                templates.stream().map(WechatSubscriptionProperties.Template::id).toList(),
                "template ids must be distinct");
        requireDistinct(
                templates.stream().map(WechatSubscriptionProperties.Template::title).toList(),
                "template titles must be distinct");
    }

    private WechatSubscriptionProperties.Template requireComplete(
            DinnerSubscriptionScenario scenario,
            WechatSubscriptionProperties.Template template
    ) {
        if (template == null
                || !hasTrimmedText(template.id())
                || !hasTrimmedText(template.title())
                || !validFieldKey(template.subjectKey())
                || !validFieldKey(template.timeKey())
                || !validFieldKey(template.noteKey())) {
            throw invalid(scenario.name() + " template configuration is incomplete");
        }
        requireDistinct(
                List.of(template.subjectKey(), template.timeKey(), template.noteKey()),
                scenario.name() + " field keys must be distinct");
        return template;
    }

    private boolean validFieldKey(String value) {
        return StringUtils.hasText(value) && FIELD_KEY.matcher(value).matches();
    }

    private boolean hasTrimmedText(String value) {
        return StringUtils.hasText(value) && value.equals(value.trim());
    }

    private void requireDistinct(List<String> values, String message) {
        Set<String> unique = new HashSet<>(values);
        if (unique.size() != values.size()) {
            throw invalid(message);
        }
    }

    private IllegalStateException invalid(String detail) {
        return new IllegalStateException(
                "Invalid WeChat subscription production configuration: " + detail);
    }
}
