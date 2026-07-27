package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipePreferenceEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipePreferenceMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DinnerRecipePreferencePersistenceContractTest {

    private static final Path V12 = Path.of(
            "src/main/resources/db/migration/"
                    + "V12__add_dinner_recipe_preferences.sql");

    @Test
    void v12BindsChoicesToMembershipCyclesAndEnforcesTheClosedValueSet()
            throws Exception {
        String sql = Files.readString(V12).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("create table dinner_recipe_preferences")
                .contains("household_id bigint not null")
                .contains("membership_id bigint not null")
                .contains("user_id bigint not null")
                .contains("recipe_id bigint not null")
                .contains("preference varchar(16) not null")
                .contains("version bigint not null default 1")
                .contains("unique key uk_dinner_recipe_preferences_membership_recipe")
                .contains("(membership_id, recipe_id)")
                .contains("foreign key (membership_id)"
                        + " references dinner_household_members (id)")
                .contains("check (preference in ('like', 'neutral', 'dislike'))")
                .contains("check (version >= 1)");
    }

    @Test
    void mapperExposesBatchReadExactVersionWriteAndLifecycleLocks() throws Exception {
        TableName table = DinnerRecipePreferenceEntity.class.getAnnotation(TableName.class);
        assertThat(table.value()).isEqualTo("dinner_recipe_preferences");
        assertField("householdId", "household_id");
        assertField("membershipId", "membership_id");
        assertField("userId", "user_id");
        assertField("recipeId", "recipe_id");

        assertThat(DinnerRecipePreferenceMapper.class.getMethod(
                "selectActiveByHouseholdAndRecipeIds", Long.class, List.class)).isNotNull();
        assertThat(DinnerRecipePreferenceMapper.class.getMethod(
                "selectByMembershipAndRecipeForUpdate", Long.class, Long.class)).isNotNull();
        assertThat(DinnerRecipePreferenceMapper.class.getMethod(
                "updatePreference",
                Long.class,
                Long.class,
                Long.class,
                Long.class,
                Long.class,
                Long.class,
                String.class)).isNotNull();
        assertThat(DinnerRecipePreferenceMapper.class.getMethod(
                "selectByHouseholdIdForUpdate", Long.class)).isNotNull();
        assertThat(DinnerRecipePreferenceMapper.class.getMethod(
                "selectByMembershipIdForUpdate", Long.class)).isNotNull();
        assertThat(DinnerRecipePreferenceMapper.class.getMethod(
                "selectByUserIdForUpdate", Long.class)).isNotNull();
    }

    private void assertField(String fieldName, String columnName) throws Exception {
        TableField field = DinnerRecipePreferenceEntity.class
                .getDeclaredField(fieldName)
                .getAnnotation(TableField.class);
        assertThat(field).isNotNull();
        assertThat(field.value()).isEqualTo(columnName);
    }
}
