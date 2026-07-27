package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateRecipePreferenceRequest(
        @NotNull RecipePreferenceValue preference,
        @NotNull @Min(0) Long version
) {
}
