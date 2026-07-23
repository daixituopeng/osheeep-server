package com.osheeep.server.dinner.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DinnerSubscriptionPersistenceContractTest {

    private static final Path V10 = Path.of(
            "src/main/resources/db/migration/"
                    + "V10__add_dinner_subscription_deliveries.sql");

    @Test
    void v10StoresOnlyControlledGrantAndDeliveryMetadata() throws Exception {
        String sql = Files.readString(V10).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("create table dinner_subscription_deliveries")
                .contains("recipient_id bigint not null")
                .contains("household_id bigint not null")
                .contains("scenario varchar(32) not null")
                .contains("request_key char(36)")
                .contains("event_dedupe_key char(64)")
                .contains("unique key uk_dinner_subscription_request")
                .contains("unique key uk_dinner_subscription_event")
                .contains("partner_joined")
                .contains("menu_changed")
                .contains("menu_completed")
                .contains("waiting_event")
                .contains("terminal_failed")
                .contains("expires_at > created_at")
                .doesNotContain("foreign key");

        assertThat(sql)
                .as("template configuration and WeChat credentials stay outside the database")
                .doesNotContain("template_id")
                .doesNotContain("template_title")
                .doesNotContain("openid")
                .doesNotContain("access_token")
                .doesNotContain("errmsg")
                .doesNotContain("message_id");
    }
}
