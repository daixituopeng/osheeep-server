package com.osheeep.server.dinner.recipe.dto;

import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import java.time.Instant;
import java.util.List;

public record RecipeDraftResponse(
        Long id,
        String status,
        Long version,
        String name,
        String category,
        String flavor,
        Integer servings,
        Integer estimatedMinutes,
        List<RecipeIngredientResponse> ingredients,
        RecipeMethodResponse defaultMethod,
        List<RecipeMethodDraftResponse> methods,
        ImageAssetResponse image,
        List<String> incompleteSteps,
        Instant updatedAt
) {
    public RecipeDraftResponse {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        methods = methods == null ? List.of() : List.copyOf(methods);
        incompleteSteps = incompleteSteps == null ? List.of() : List.copyOf(incompleteSteps);
    }

    public RecipeDraftResponse(
            Long id,
            String status,
            Long version,
            String name,
            String category,
            String flavor,
            Integer servings,
            Integer estimatedMinutes,
            List<RecipeIngredientResponse> ingredients,
            RecipeMethodResponse defaultMethod,
            ImageAssetResponse image,
            List<String> incompleteSteps,
            Instant updatedAt
    ) {
        this(
                id, status, version, name, category, flavor, servings, estimatedMinutes,
                ingredients, defaultMethod, legacyMethods(defaultMethod, estimatedMinutes),
                image, incompleteSteps, updatedAt);
    }

    private static List<RecipeMethodDraftResponse> legacyMethods(
            RecipeMethodResponse defaultMethod,
            Integer estimatedMinutes
    ) {
        if (defaultMethod == null) {
            return List.of();
        }
        return List.of(new RecipeMethodDraftResponse(
                defaultMethod.id(), defaultMethod.name(), defaultMethod.cookingStyle(),
                estimatedMinutes, true, 0, defaultMethod.steps()));
    }
}
