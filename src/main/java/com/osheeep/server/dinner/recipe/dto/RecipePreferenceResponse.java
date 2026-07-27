package com.osheeep.server.dinner.recipe.dto;

public record RecipePreferenceResponse(
        RecipePreferenceValue myPreference,
        long myVersion,
        HouseholdRecipePreference householdPreference
) {
    public static RecipePreferenceResponse neutral() {
        return new RecipePreferenceResponse(
                RecipePreferenceValue.NEUTRAL,
                0L,
                HouseholdRecipePreference.NEUTRAL);
    }
}
