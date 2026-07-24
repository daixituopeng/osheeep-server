package com.osheeep.server.dinner.record.dto;

import java.math.BigDecimal;

public record InventoryDeductionAppliedItemResponse(
        Long ingredientId,
        String name,
        String unit,
        BigDecimal deductedQuantity,
        BigDecimal quantityBefore,
        BigDecimal quantityAfter,
        Long resultingVersion
) {
}
