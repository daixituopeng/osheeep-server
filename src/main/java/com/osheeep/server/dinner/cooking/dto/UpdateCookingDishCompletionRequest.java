package com.osheeep.server.dinner.cooking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateCookingDishCompletionRequest(
        @NotNull Boolean completed,
        @NotNull @PositiveOrZero Long version
) {
}
