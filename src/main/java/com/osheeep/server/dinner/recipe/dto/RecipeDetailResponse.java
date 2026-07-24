package com.osheeep.server.dinner.recipe.dto;

import java.util.List;

public record RecipeDetailResponse(
        Long id,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer servings,
        Integer estimatedMinutes,
        String scope,
        Long version,
        List<RecipeIngredientResponse> ingredients,
        RecipeMatchResponse match,
        List<RecipeMethodOptionResponse> methods
) {
    public RecipeDetailResponse {
        ingredients = List.copyOf(ingredients);
        methods = List.copyOf(methods);
    }
}
