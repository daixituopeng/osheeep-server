package com.osheeep.server.dinner.menu.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record UpdateSelectionsRequest(
        List<@NotNull @Positive Long> recipeIds,
        List<@NotNull @Valid MenuSelectionRequest> selections,
        @NotNull @PositiveOrZero Long version
) {
    public UpdateSelectionsRequest(List<Long> recipeIds, Long version) {
        this(recipeIds, null, version);
    }
}
