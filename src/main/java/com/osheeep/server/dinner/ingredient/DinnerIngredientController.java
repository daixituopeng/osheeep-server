package com.osheeep.server.dinner.ingredient;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.ingredient.dto.CreateHouseholdIngredientRequest;
import com.osheeep.server.dinner.ingredient.dto.IngredientResponse;
import com.osheeep.server.dinner.ingredient.dto.InventoryItemResponse;
import com.osheeep.server.dinner.ingredient.dto.UpsertInventoryItemRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner")
public class DinnerIngredientController {

    private final DinnerIngredientService ingredientService;
    private final DinnerHouseholdIngredientService householdIngredientService;

    public DinnerIngredientController(
            DinnerIngredientService ingredientService,
            DinnerHouseholdIngredientService householdIngredientService
    ) {
        this.ingredientService = ingredientService;
        this.householdIngredientService = householdIngredientService;
    }

    @PostMapping("/ingredients")
    public ApiResponse<IngredientResponse> createHouseholdIngredient(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateHouseholdIngredientRequest request
    ) {
        return ApiResponse.ok(householdIngredientService.create(
                currentUser.id(),
                request.name(),
                request.category(),
                request.defaultUnit()));
    }

    @GetMapping("/ingredients")
    public ApiResponse<List<IngredientResponse>> listIngredients(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(ingredientService.listIngredients(currentUser.id()));
    }

    @GetMapping("/inventory")
    public ApiResponse<List<InventoryItemResponse>> listInventory(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(ingredientService.listInventory(currentUser.id()));
    }

    @PutMapping("/inventory/{ingredientId}")
    public ApiResponse<InventoryItemResponse> upsertInventoryItem(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long ingredientId,
            @Valid @RequestBody UpsertInventoryItemRequest request
    ) {
        return ApiResponse.ok(ingredientService.upsertInventoryItem(
                currentUser.id(), ingredientId, request.quantity(), request.unit(), request.version()));
    }

    @DeleteMapping("/inventory/{ingredientId}")
    public ApiResponse<Void> removeInventoryItem(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long ingredientId,
            @RequestParam long version
    ) {
        ingredientService.removeInventoryItem(currentUser.id(), ingredientId, version);
        return ApiResponse.ok(null);
    }
}
