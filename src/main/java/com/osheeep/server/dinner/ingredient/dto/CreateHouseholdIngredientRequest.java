package com.osheeep.server.dinner.ingredient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateHouseholdIngredientRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 32) String category,
        @NotBlank @Size(max = 16) String defaultUnit
) {
}

