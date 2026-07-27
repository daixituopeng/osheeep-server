package com.osheeep.server.dinner.recipe;

import com.osheeep.server.dinner.recipe.dto.HouseholdRecipePreference;
import com.osheeep.server.dinner.recipe.dto.RecipePreferenceResponse;
import com.osheeep.server.dinner.recipe.dto.RecipePreferenceValue;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipePreferenceEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DinnerRecipePreferenceAggregator {

    public Map<Long, RecipePreferenceResponse> aggregate(
            List<Long> recipeIds,
            Long currentUserId,
            List<DinnerRecipePreferenceEntity> rows
    ) {
        Objects.requireNonNull(currentUserId, "Current user id is required");
        Set<Long> requested = new HashSet<>(recipeIds);
        if (requested.size() != recipeIds.size() || requested.contains(null)) {
            throw new IllegalArgumentException("Recipe ids must be distinct and non-null");
        }
        Map<Long, List<DinnerRecipePreferenceEntity>> grouped = new HashMap<>();
        for (DinnerRecipePreferenceEntity row : rows) {
            if (row == null || !requested.contains(row.getRecipeId())) {
                throw new IllegalStateException("Recipe preference aggregate is invalid");
            }
            grouped.computeIfAbsent(row.getRecipeId(), ignored -> new java.util.ArrayList<>())
                    .add(row);
        }

        Map<Long, RecipePreferenceResponse> result = new LinkedHashMap<>();
        for (Long recipeId : recipeIds) {
            result.put(recipeId, summarize(
                    currentUserId, grouped.getOrDefault(recipeId, List.of())));
        }
        return Map.copyOf(result);
    }

    public int rank(HouseholdRecipePreference preference) {
        return switch (preference) {
            case BOTH_LIKE -> 0;
            case SOME_LIKE -> 1;
            case NEUTRAL -> 2;
            case MIXED -> 3;
            case SOME_DISLIKE -> 4;
            case BOTH_DISLIKE -> 5;
        };
    }

    private RecipePreferenceResponse summarize(
            Long currentUserId,
            List<DinnerRecipePreferenceEntity> rows
    ) {
        if (rows.size() > 2) {
            throw new IllegalStateException("Active household preference set is invalid");
        }
        Set<Long> membershipIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        RecipePreferenceValue mine = RecipePreferenceValue.NEUTRAL;
        long myVersion = 0L;
        int likes = 0;
        int dislikes = 0;
        for (DinnerRecipePreferenceEntity row : rows) {
            if (row.getMembershipId() == null
                    || row.getUserId() == null
                    || row.getVersion() == null
                    || row.getVersion() < 1
                    || !membershipIds.add(row.getMembershipId())
                    || !userIds.add(row.getUserId())) {
                throw new IllegalStateException("Active household preference set is invalid");
            }
            RecipePreferenceValue value;
            try {
                value = RecipePreferenceValue.valueOf(row.getPreference());
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "Recipe preference value is invalid", exception);
            }
            if (value == RecipePreferenceValue.LIKE) {
                likes++;
            } else if (value == RecipePreferenceValue.DISLIKE) {
                dislikes++;
            }
            if (Objects.equals(currentUserId, row.getUserId())) {
                mine = value;
                myVersion = row.getVersion();
            }
        }
        return new RecipePreferenceResponse(
                mine,
                myVersion,
                householdPreference(likes, dislikes));
    }

    private HouseholdRecipePreference householdPreference(int likes, int dislikes) {
        if (likes == 2) {
            return HouseholdRecipePreference.BOTH_LIKE;
        }
        if (dislikes == 2) {
            return HouseholdRecipePreference.BOTH_DISLIKE;
        }
        if (likes > 0 && dislikes > 0) {
            return HouseholdRecipePreference.MIXED;
        }
        if (dislikes > 0) {
            return HouseholdRecipePreference.SOME_DISLIKE;
        }
        if (likes > 0) {
            return HouseholdRecipePreference.SOME_LIKE;
        }
        return HouseholdRecipePreference.NEUTRAL;
    }
}
