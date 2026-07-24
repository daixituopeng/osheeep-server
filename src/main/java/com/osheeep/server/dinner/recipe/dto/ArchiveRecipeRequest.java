package com.osheeep.server.dinner.recipe.dto;

import jakarta.validation.constraints.Min;

public record ArchiveRecipeRequest(@Min(1) long version) {
}
