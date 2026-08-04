package com.osheeep.server.dinner.ingredient.dto;

public record IngredientResponse(
        Long id,
        String name,
        String category,
        String defaultUnit,
        String scope,
        String imageUrl
) {
    public IngredientResponse(
            Long id,
            String name,
            String category,
            String defaultUnit,
            String scope
    ) {
        this(id, name, category, defaultUnit, scope, null);
    }
}
