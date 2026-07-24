package com.osheeep.server.dinner.menu.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MenuSelectionRequest(
        @NotNull @Positive Long recipeId,
        @Positive Long methodId
) {
}
