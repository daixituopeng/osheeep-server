package com.osheeep.server.dinner.menu.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MenuMethodResolutionRequest(
        @NotNull @Positive Long recipeId,
        @NotNull @Positive Long methodId
) {
}
