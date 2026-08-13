package com.osheeep.server.dinner.cooking.dto;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import java.time.Instant;
import java.util.List;

public record CookingDishResponse(
        Long id,
        Long recipeId,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer estimatedMinutes,
        String scope,
        Long recipeVersion,
        Integer servings,
        CookingMethodSnapshotResponse method,
        List<RecordIngredientSnapshotResponse> ingredients,
        String origin,
        List<HouseholdActorResponse> selectedBy,
        HouseholdActorResponse addedBy,
        boolean completed,
        HouseholdActorResponse completedBy,
        Instant completedAt,
        Integer sortOrder
) {
    public CookingDishResponse {
        ingredients = List.copyOf(ingredients);
        selectedBy = List.copyOf(selectedBy);
    }
}
