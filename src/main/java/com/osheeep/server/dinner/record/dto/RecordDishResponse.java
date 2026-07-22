package com.osheeep.server.dinner.record.dto;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import java.util.List;

public record RecordDishResponse(
        Long recipeId,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer estimatedMinutes,
        String source,
        List<HouseholdActorResponse> selectedBy,
        String scope,
        Long recipeVersion,
        Integer servings,
        RecordMethodSnapshotResponse method,
        List<RecordIngredientSnapshotResponse> ingredients
) {
    public RecordDishResponse {
        selectedBy = List.copyOf(selectedBy);
        ingredients = List.copyOf(ingredients);
    }
}
