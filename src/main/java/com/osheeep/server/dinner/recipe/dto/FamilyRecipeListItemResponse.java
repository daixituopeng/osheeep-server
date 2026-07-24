package com.osheeep.server.dinner.recipe.dto;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import java.time.Instant;

public record FamilyRecipeListItemResponse(
        Long id,
        String status,
        String name,
        String imageUrl,
        String category,
        String flavor,
        Integer servings,
        Integer estimatedMinutes,
        Long version,
        HouseholdActorResponse creator,
        HouseholdActorResponse lastModifier,
        String completedStep,
        Instant updatedAt,
        Long revisionOfRecipeId
) {
    public FamilyRecipeListItemResponse(
            Long id,
            String status,
            String name,
            String imageUrl,
            String category,
            String flavor,
            Integer servings,
            Integer estimatedMinutes,
            Long version,
            HouseholdActorResponse creator,
            HouseholdActorResponse lastModifier,
            String completedStep,
            Instant updatedAt
    ) {
        this(
                id, status, name, imageUrl, category, flavor, servings, estimatedMinutes,
                version, creator, lastModifier, completedStep, updatedAt, null);
    }
}
