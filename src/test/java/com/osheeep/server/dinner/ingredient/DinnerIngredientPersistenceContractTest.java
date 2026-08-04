package com.osheeep.server.dinner.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DinnerIngredientPersistenceContractTest {

    @Test
    void inventoryExposesOptimisticVersionAndLockingLookup() throws Exception {
        DinnerHouseholdInventoryEntity item = new DinnerHouseholdInventoryEntity();
        item.setHouseholdId(11L);
        item.setIngredientId(3L);
        item.setQuantity(new BigDecimal("8.000"));
        item.setUnit("枚");
        item.setVersion(2L);

        assertThat(item.getVersion()).isEqualTo(2L);
        assertThat(DinnerHouseholdInventoryMapper.class.getMethod(
                "selectByHouseholdAndIngredientForUpdate", Long.class, Long.class)).isNotNull();
    }

    @Test
    void inventoryMigrationReservesZeroForCreateOnlyRequests() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V5__add_recipe_ingredients_and_household_inventory.sql"));

        assertThat(migration).contains("version BIGINT NOT NULL DEFAULT 1");
    }

    @Test
    void ingredientImageMigrationLinksReviewedAssetsToSystemIngredients() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V13__add_ingredient_image_assets.sql"));

        assertThat(migration)
                .contains("ADD COLUMN image_asset_id BIGINT NULL")
                .contains("media/ingredients/tomato-list.webp")
                .contains("WHERE scope = 'SYSTEM' AND name IN ('番茄')");
        assertThat(migration.split("INSERT INTO dinner_image_assets", -1)).hasSize(25);
        assertThat(migration.split("SET image_asset_id", -1)).hasSize(25);
    }
}
