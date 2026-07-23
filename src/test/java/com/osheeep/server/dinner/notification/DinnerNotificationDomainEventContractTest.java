package com.osheeep.server.dinner.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DinnerNotificationDomainEventContractTest {

    private static final Path SOURCE = Path.of("src/main/java/com/osheeep/server");

    @Test
    void coreHouseholdEventsPublishTheirControlledNotificationTypes() throws Exception {
        assertSource("dinner/household/DinnerHouseholdWriteService.java", "PARTNER_JOINED");
        assertSource(
                "dinner/menu/DinnerMenuService.java",
                "PARTNER_SELECTION_UPDATED",
                "MENU_RECONFIRM_REQUIRED");
        assertSource("dinner/record/DinnerRecordService.java", "MENU_COMPLETED");
        assertSource("dinner/recipe/DinnerRecipePublishTransaction.java", "FAMILY_RECIPE_UPDATED");
        assertSource("dinner/ingredient/DinnerIngredientService.java", "INVENTORY_UPDATED");
        assertSource(
                "dinner/household/DinnerHouseholdOwnershipService.java",
                "OWNERSHIP_TRANSFERRED");
        assertSource(
                "dinner/household/DinnerMembershipTerminationService.java",
                "MEMBER_LEFT",
                "MEMBER_REMOVED");
    }

    @Test
    void destructiveCleanupRemovesRecipientAndHouseholdScopedNotifications() throws Exception {
        assertSource(
                "dinner/household/DinnerAccountCleanupService.java",
                "deleteByRecipientId");
        assertSource(
                "dinner/household/DinnerHouseholdDataPurger.java",
                "deleteByHouseholdId");
    }

    private void assertSource(String relativePath, String... fragments) throws Exception {
        String source = Files.readString(SOURCE.resolve(relativePath));
        assertThat(source).contains(fragments);
    }
}
