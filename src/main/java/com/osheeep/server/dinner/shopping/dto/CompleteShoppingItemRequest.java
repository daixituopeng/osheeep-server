package com.osheeep.server.dinner.shopping.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CompleteShoppingItemRequest(
        @NotNull @Positive Long ingredientId,
        @NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3)
        BigDecimal quantity,
        @NotBlank @Size(max = 16) String unit
) {
}
