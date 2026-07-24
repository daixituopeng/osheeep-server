package com.osheeep.server.dinner.recipe.dto;

import java.util.List;

public record RecipeMethodOptionResponse(
        Long id,
        String name,
        String cookingStyle,
        Integer estimatedMinutes,
        boolean defaultMethod,
        List<RecipeMethodStepResponse> steps
) {
    public RecipeMethodOptionResponse {
        steps = List.copyOf(steps);
    }
}
