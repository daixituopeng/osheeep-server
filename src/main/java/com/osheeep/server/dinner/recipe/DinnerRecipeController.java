package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.recipe.dto.RecipePreferenceResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeDetailResponse;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipePreferenceRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/dinner/recipes")
public class DinnerRecipeController {

    private final DinnerRecipeService recipeService;
    private final DinnerRecipePreferenceService preferenceService;

    public DinnerRecipeController(
            DinnerRecipeService recipeService,
            DinnerRecipePreferenceService preferenceService
    ) {
        this.recipeService = recipeService;
        this.preferenceService = preferenceService;
    }

    @GetMapping
    public ApiResponse<List<RecipeResponse>> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "") Set<Long> includeIngredientIds,
            @RequestParam(defaultValue = "") Set<Long> excludeIngredientIds,
            @RequestParam(defaultValue = "false") boolean onlyCookable
    ) {
        return ApiResponse.ok(recipeService.discover(
                currentUser.id(), includeIngredientIds, excludeIngredientIds, onlyCookable));
    }

    @GetMapping("/{recipeId}/view")
    public ApiResponse<RecipeDetailResponse> detail(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long recipeId
    ) {
        return ApiResponse.ok(recipeService.detail(currentUser.id(), recipeId));
    }

    @PutMapping("/{recipeId}/preference")
    public ApiResponse<RecipePreferenceResponse> updatePreference(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long recipeId,
            @Valid @RequestBody UpdateRecipePreferenceRequest request
    ) {
        return ApiResponse.ok(preferenceService.update(currentUser.id(), recipeId, request));
    }
}
