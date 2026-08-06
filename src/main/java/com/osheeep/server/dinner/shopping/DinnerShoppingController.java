package com.osheeep.server.dinner.shopping;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.shopping.dto.CompleteShoppingRequest;
import com.osheeep.server.dinner.shopping.dto.CompleteShoppingResponse;
import com.osheeep.server.dinner.shopping.dto.ShoppingItemResponse;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/shopping")
public class DinnerShoppingController {

    private final DinnerShoppingService shoppingService;

    public DinnerShoppingController(DinnerShoppingService shoppingService) {
        this.shoppingService = shoppingService;
    }

    @GetMapping("/items")
    public ApiResponse<List<ShoppingItemResponse>> listItems(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(shoppingService.listItems(currentUser.id()));
    }

    @PutMapping("/items/{ingredientId}")
    public ApiResponse<ShoppingItemResponse> addItem(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long ingredientId
    ) {
        return ApiResponse.ok(shoppingService.addItem(currentUser.id(), ingredientId));
    }

    @DeleteMapping("/items/{ingredientId}")
    public ApiResponse<Void> removeItem(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long ingredientId
    ) {
        shoppingService.removeItem(currentUser.id(), ingredientId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/complete")
    public ApiResponse<CompleteShoppingResponse> complete(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CompleteShoppingRequest request
    ) {
        return ApiResponse.ok(shoppingService.complete(currentUser.id(), request.items()));
    }
}
