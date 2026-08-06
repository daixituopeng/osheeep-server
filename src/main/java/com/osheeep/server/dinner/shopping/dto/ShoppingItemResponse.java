package com.osheeep.server.dinner.shopping.dto;

import java.time.Instant;

public record ShoppingItemResponse(
        Long ingredientId,
        Long addedBy,
        Instant createdAt
) {
}
