package com.osheeep.server.dinner.record.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record InventoryDeductionItemRequest(
        @NotNull @Positive Long ingredientId,
        @NotNull @DecimalMin(value = "0.001") @Digits(integer = 9, fraction = 3)
        BigDecimal quantity,
        @NotNull @Positive Long inventoryVersion
) {
}
