package com.osheeep.server.dinner.recipe.dto;

import java.util.List;

public record RecipeMethodDraftResponse(
        Long id,
        String name,
        String cookingStyle,
        Integer estimatedMinutes,
        boolean defaultMethod,
        int sortOrder,
        List<RecipeMethodStepResponse> steps
) {
    public RecipeMethodDraftResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
