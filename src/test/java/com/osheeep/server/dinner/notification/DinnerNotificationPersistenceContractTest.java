package com.osheeep.server.dinner.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DinnerNotificationPersistenceContractTest {

    private static final Path V9 = Path.of(
            "src/main/resources/db/migration/V9__add_dinner_notifications.sql");

    @Test
    void v9CreatesPrivacySafeScopedAndDeduplicatedNotifications() throws Exception {
        String sql = Files.readString(V9).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("create table dinner_notifications")
                .contains("recipient_id bigint not null")
                .contains("household_id bigint null")
                .contains("type varchar(40) not null")
                .contains("reference_type varchar(32) not null")
                .contains("reference_id bigint not null")
                .contains("reference_version bigint null")
                .contains("dedupe_key char(64) character set ascii collate ascii_bin not null")
                .contains("read_at datetime(3) null")
                .contains("created_at datetime(3) not null")
                .contains("expires_at datetime(3) not null")
                .contains("unique key uk_dinner_notifications_dedupe (dedupe_key)")
                .contains("key idx_dinner_notifications_recipient_feed")
                .contains("key idx_dinner_notifications_expiry");

        assertThat(sql)
                .contains("partner_joined")
                .contains("partner_selection_updated")
                .contains("menu_reconfirm_required")
                .contains("menu_completed")
                .contains("family_recipe_updated")
                .contains("inventory_updated")
                .contains("ownership_transferred")
                .contains("member_left")
                .contains("member_removed")
                .contains("expires_at > created_at")
                .contains("recipient_id > 0")
                .contains("reference_id > 0");

        assertThat(sql)
                .as("notification writes occur at the end of existing household transactions")
                .doesNotContain("foreign key");
        assertThat(sql)
                .as("notifications must not persist actor identities, free text or arbitrary URLs")
                .doesNotContain("actor_id")
                .doesNotContain("openid")
                .doesNotContain("invite_code")
                .doesNotContain("title varchar")
                .doesNotContain("body varchar")
                .doesNotContain("url varchar");
    }
}
