package com.osheeep.server.dinner.recipe.dto;

import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import java.util.List;

public record RecipeResponse(
        Long id,
        String name,
        String imagePath,
        String category,
        String flavor,
        Integer estimatedMinutes,
        String scope,
        Long version,
        RecipeMethodSummaryResponse defaultMethod,
        List<RecipeIngredientResponse> ingredients,
        RecipeMatchResponse match,
        RecipePreferenceResponse preference
) {
    public RecipeResponse(
            Long id,
            String name,
            String imagePath,
            String category,
            String flavor,
            Integer estimatedMinutes,
            String scope,
            Long version,
            RecipeMethodSummaryResponse defaultMethod,
            List<RecipeIngredientResponse> ingredients,
            RecipeMatchResponse match
    ) {
        this(id, name, imagePath, category, flavor, estimatedMinutes, scope, version,
                defaultMethod, ingredients, match, RecipePreferenceResponse.neutral());
    }

    public RecipeResponse(
            Long id,
            String name,
            String imagePath,
            String category,
            String flavor,
            Integer estimatedMinutes
    ) {
        this(id, name, imagePath, category, flavor, estimatedMinutes,
                "SYSTEM", 1L, null, List.of(), null, RecipePreferenceResponse.neutral());
    }

    public RecipeResponse(
            Long id,
            String name,
            String imagePath,
            String category,
            String flavor,
            Integer estimatedMinutes,
            List<RecipeIngredientResponse> ingredients,
            RecipeMatchResponse match
    ) {
        this(id, name, imagePath, category, flavor, estimatedMinutes,
                "SYSTEM", 1L, null, ingredients, match, RecipePreferenceResponse.neutral());
    }

    public static RecipeResponse from(DinnerRecipeEntity recipe) {
        return new RecipeResponse(
                recipe.getId(), recipe.getName(), recipe.getImagePath(), recipe.getCategory(),
                recipe.getFlavor(), recipe.getEstimatedMinutes());
    }
}
