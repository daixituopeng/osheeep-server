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
        RecipeMethodSummaryResponse method,
        List<MenuMethodChoiceResponse> methodChoices,
        boolean methodConflict
) {
    public MenuDishResponse {
        selectedBy = List.copyOf(selectedBy);
        methodChoices = List.copyOf(methodChoices);
    }

    public MenuDishResponse(
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
        this(recipeId, name, imagePath, category, flavor, estimatedMinutes, source,
                selectedBy, scope, recipeVersion, method,
                method == null
                        ? List.of()
                        : List.of(new MenuMethodChoiceResponse(method, selectedBy)),
                false);
    }
}
