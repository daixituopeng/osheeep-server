package com.osheeep.server.dinner.cooking;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.cooking.dto.AddCookingDishRequest;
import com.osheeep.server.dinner.cooking.dto.CookingSessionResponse;
import com.osheeep.server.dinner.cooking.dto.StartCookingRequest;
import com.osheeep.server.dinner.cooking.dto.UpdateCookingDishCompletionRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/menus/today/cooking")
public class DinnerCookingController {

    private final DinnerCookingService cookingService;

    public DinnerCookingController(DinnerCookingService cookingService) {
        this.cookingService = cookingService;
    }

    @GetMapping
    public ApiResponse<CookingSessionResponse> get(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(cookingService.get(currentUser.id()));
    }

    @PostMapping("/start")
    public ApiResponse<CookingSessionResponse> start(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody StartCookingRequest request
    ) {
        return ApiResponse.ok(cookingService.start(currentUser.id(), request));
    }

    @PostMapping("/dishes")
    public ApiResponse<CookingSessionResponse> addDish(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody AddCookingDishRequest request
    ) {
        return ApiResponse.ok(cookingService.addDish(currentUser.id(), request));
    }

    @PutMapping("/dishes/{dishId}/completion")
    public ApiResponse<CookingSessionResponse> setCompleted(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long dishId,
            @Valid @RequestBody UpdateCookingDishCompletionRequest request
    ) {
        return ApiResponse.ok(cookingService.setCompleted(currentUser.id(), dishId, request));
    }
}
