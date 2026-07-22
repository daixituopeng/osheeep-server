package com.osheeep.server.dinner.menu.dto;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import java.util.List;

public record MenuDishResponse(
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
        RecipeMethodSummaryResponse method
) {
    public MenuDishResponse {
        selectedBy = List.copyOf(selectedBy);
    }
}
