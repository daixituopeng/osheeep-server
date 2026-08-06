package com.osheeep.server.dinner.shopping;

import static org.assertj.core.api.Assertions.assertThat;

import com.osheeep.server.dinner.shopping.mapper.DinnerHouseholdShoppingItemMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DinnerShoppingPersistenceContractTest {

    @Test
    void migrationCreatesAHouseholdSharedIdempotentShoppingList() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V14__add_household_shopping_items.sql"));

        assertThat(migration)
                .contains("CREATE TABLE dinner_household_shopping_items")
                .contains("UNIQUE KEY uk_dinner_household_shopping_item "
                        + "(household_id, ingredient_id)")
                .contains("REFERENCES dinner_households (id) ON DELETE CASCADE")
                .contains("REFERENCES dinner_ingredients (id) ON DELETE CASCADE")
                .contains("REFERENCES users (id) ON DELETE SET NULL");
    }

    @Test
    void mapperExposesDeterministicHouseholdLocks() throws Exception {
        assertThat(DinnerHouseholdShoppingItemMapper.class.getMethod(
                "selectByHouseholdAndIngredientForUpdate", Long.class, Long.class))
                .isNotNull();
        assertThat(DinnerHouseholdShoppingItemMapper.class.getMethod(
                "selectAllByHouseholdIdForUpdate", Long.class)).isNotNull();
    }
}
