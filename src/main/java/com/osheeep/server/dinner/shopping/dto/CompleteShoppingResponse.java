package com.osheeep.server.dinner.shopping.dto;

import com.osheeep.server.dinner.ingredient.dto.InventoryItemResponse;
import java.util.List;

public record CompleteShoppingResponse(
        List<InventoryItemResponse> inventory,
        List<ShoppingItemResponse> remainingItems
) {
}
