package com.osheeep.server.dinner.cooking;

import static org.assertj.core.api.Assertions.assertThat;

import com.osheeep.server.dinner.cooking.entity.DinnerMenuCookingDishEntity;
import com.osheeep.server.dinner.cooking.mapper.DinnerMenuCookingDishMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class DinnerCookingPersistenceContractTest {

    private static final Path V15 = Path.of(
            "src/main/resources/db/migration/"
                    + "V15__add_menu_cooking_dish_snapshots.sql");

    @Test
    void v15AddsFrozenCookingRowsAndRecordOriginWithoutLiveActorForeignKeys()
            throws Exception {
        String sql = Files.readString(V15).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("alter table dinner_record_dish_snapshots")
                .contains("add column origin varchar(16) not null default 'planned'")
                .contains("create table dinner_menu_cooking_dishes")
                .contains("method_steps json not null")
                .contains("ingredients json not null")
                .contains("selected_by_user_ids json not null")
                .contains("origin varchar(16) not null")
                .contains("unique key uk_dinner_cooking_dish_recipe (menu_id, recipe_id)")
                .contains("unique key uk_dinner_cooking_dish_add_key (add_idempotency_key)")
                .contains("foreign key (menu_id) references dinner_menus (id)")
                .contains("origin = 'planned'")
                .contains("origin = 'temporary'")
                .contains("completed_by is null and completed_at is null")
                .contains("completed_by is not null and completed_at is not null");

        assertThat(sql)
                .as("frozen actor semantics must not block member exit or account anonymization")
                .doesNotContain("foreign key (added_by)")
                .doesNotContain("foreign key (completed_by)")
                .doesNotContain("references dinner_recipes")
                .doesNotContain("references dinner_recipe_methods")
                .doesNotContain("references dinner_ingredients");
    }

    @Test
    void entityAndMapperExposeTheCompleteFrozenAndDeterministicLockContract()
            throws Exception {
        DinnerMenuCookingDishEntity row = new DinnerMenuCookingDishEntity();
        row.setMenuId(31L);
        row.setRecipeId(14L);
        row.setRecipeScope("HOUSEHOLD");
        row.setRecipeVersion(8L);
        row.setMethodStepsJson("[]");
        row.setIngredients("[]");
        row.setSelectedByUserIds("[7]");
        row.setOrigin("TEMPORARY");
        row.setCompletedAt(LocalDateTime.of(2026, 8, 13, 11, 0));

        assertThat(row.getMenuId()).isEqualTo(31L);
        assertThat(row.getRecipeVersion()).isEqualTo(8L);
        assertThat(row.getOrigin()).isEqualTo("TEMPORARY");
        assertThat(DinnerMenuCookingDishMapper.class.getMethod(
                "selectByMenuIdForUpdate", Long.class)).isNotNull();
        assertThat(DinnerMenuCookingDishMapper.class.getMethod(
                "selectByMenuIdsForUpdate", List.class)).isNotNull();

        Update mark = DinnerMenuCookingDishMapper.class.getMethod(
                        "markCompleted", Long.class, Long.class,
                        Long.class, LocalDateTime.class)
                .getAnnotation(Update.class);
        Update clear = DinnerMenuCookingDishMapper.class.getMethod(
                        "clearCompletion", Long.class, Long.class)
                .getAnnotation(Update.class);
        assertThat(String.join(" ", mark.value()).toLowerCase())
                .contains("set completed_by = #{completedby}, completed_at = #{completedat}")
                .contains("id = #{dishid}")
                .contains("menu_id = #{menuid}");
        assertThat(String.join(" ", clear.value()).toLowerCase())
                .contains("set completed_by = null, completed_at = null")
                .contains("id = #{dishid}")
                .contains("menu_id = #{menuid}");
    }
}
