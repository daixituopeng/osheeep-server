package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReplaceRecipeMethodsRequest(
        @Min(1) long version,
        @NotNull @Size(min = 1, max = 8) @Valid List<RecipeMethodInput> methods
) {
}
