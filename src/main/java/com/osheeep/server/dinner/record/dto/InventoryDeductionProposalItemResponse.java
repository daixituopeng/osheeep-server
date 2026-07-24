package com.osheeep.server.dinner.record.dto;

import java.math.BigDecimal;

public record InventoryDeductionProposalItemResponse(
        Long ingredientId,
        String name,
        BigDecimal recipeQuantity,
        String recipeUnit,
        boolean required,
        BigDecimal inventoryQuantity,
        String inventoryUnit,
        Long inventoryVersion,
        BigDecimal suggestedQuantity,
        boolean selectedByDefault,
        String eligibility
) {
}
