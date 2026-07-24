package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RecipeMethodInput(
        @Positive Long id,
        @NotBlank @Size(max = 40) String name,
        @NotBlank @Size(max = 32) String cookingStyle,
        @NotNull @Min(1) @Max(1440) Integer estimatedMinutes,
        boolean defaultMethod,
        @NotNull @Size(min = 1, max = 12) @Valid List<RecipeMethodStepInput> steps
) {
}
