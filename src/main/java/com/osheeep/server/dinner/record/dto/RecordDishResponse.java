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
        List<RecordIngredientSnapshotResponse> ingredients,
        String origin
) {
    public RecordDishResponse {
        selectedBy = List.copyOf(selectedBy);
        ingredients = List.copyOf(ingredients);
    }

    public RecordDishResponse(
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
        this(recipeId, name, imagePath, category, flavor, estimatedMinutes, source,
                selectedBy, scope, recipeVersion, servings, method, ingredients, "PLANNED");
    }
}
