package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.osheeep.server.dinner.recipe.dto.HouseholdRecipePreference;
import com.osheeep.server.dinner.recipe.dto.RecipePreferenceValue;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipePreferenceEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class DinnerRecipePreferenceAggregatorTest {

    private final DinnerRecipePreferenceAggregator aggregator =
            new DinnerRecipePreferenceAggregator();

    @Test
    void summarizesBothMembersWithoutExposingTheirIdentities() {
        var summaries = aggregator.aggregate(
                List.of(10L, 11L, 12L),
                7L,
                List.of(
                        preference(1L, 31L, 7L, 10L, "LIKE", 2L),
                        preference(2L, 32L, 8L, 10L, "LIKE", 1L),
                        preference(3L, 31L, 7L, 11L, "LIKE", 3L),
                        preference(4L, 32L, 8L, 11L, "DISLIKE", 4L)));

        assertThat(summaries.get(10L).myPreference())
                .isEqualTo(RecipePreferenceValue.LIKE);
        assertThat(summaries.get(10L).myVersion()).isEqualTo(2L);
        assertThat(summaries.get(10L).householdPreference())
                .isEqualTo(HouseholdRecipePreference.BOTH_LIKE);
        assertThat(summaries.get(11L).householdPreference())
                .isEqualTo(HouseholdRecipePreference.MIXED);
        assertThat(summaries.get(12L).myPreference())
                .isEqualTo(RecipePreferenceValue.NEUTRAL);
        assertThat(summaries.get(12L).myVersion()).isZero();
        assertThat(summaries.get(12L).householdPreference())
                .isEqualTo(HouseholdRecipePreference.NEUTRAL);
    }

    @Test
    void rankingKeepsPositiveSignalsFirstAndNegativeSignalsLast() {
        assertThat(List.of(HouseholdRecipePreference.values()).stream()
                .sorted(java.util.Comparator.comparingInt(aggregator::rank))
                .toList())
                .containsExactly(
                        HouseholdRecipePreference.BOTH_LIKE,
                        HouseholdRecipePreference.SOME_LIKE,
                        HouseholdRecipePreference.NEUTRAL,
                        HouseholdRecipePreference.MIXED,
                        HouseholdRecipePreference.SOME_DISLIKE,
                        HouseholdRecipePreference.BOTH_DISLIKE);
    }

    @Test
    void rejectsDuplicateMembershipRowsInsteadOfPublishingAFalseAggregate() {
        assertThatThrownBy(() -> aggregator.aggregate(
                List.of(10L),
                7L,
                List.of(
                        preference(1L, 31L, 7L, 10L, "LIKE", 1L),
                        preference(2L, 31L, 7L, 10L, "DISLIKE", 2L))))
                .isInstanceOf(IllegalStateException.class);
    }

    private DinnerRecipePreferenceEntity preference(
            Long id,
            Long membershipId,
            Long userId,
            Long recipeId,
            String value,
            Long version
    ) {
        DinnerRecipePreferenceEntity preference = new DinnerRecipePreferenceEntity();
        preference.setId(id);
        preference.setHouseholdId(70L);
        preference.setMembershipId(membershipId);
        preference.setUserId(userId);
        preference.setRecipeId(recipeId);
        preference.setPreference(value);
        preference.setVersion(version);
        return preference;
    }
}
