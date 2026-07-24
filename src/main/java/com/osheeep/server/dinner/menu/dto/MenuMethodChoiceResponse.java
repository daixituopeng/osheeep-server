package com.osheeep.server.dinner.menu.dto;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import java.util.List;

public record MenuMethodChoiceResponse(
        RecipeMethodSummaryResponse method,
        List<HouseholdActorResponse> selectedBy
) {
    public MenuMethodChoiceResponse {
        selectedBy = List.copyOf(selectedBy);
    }
}
