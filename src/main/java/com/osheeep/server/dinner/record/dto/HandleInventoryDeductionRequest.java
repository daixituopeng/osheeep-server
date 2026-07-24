package com.osheeep.server.dinner.record.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record HandleInventoryDeductionRequest(
        @NotBlank @Pattern(regexp = "^(APPLY|SKIP)$") String action,
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        String idempotencyKey,
        @NotNull @Size(max = 100) List<@Valid InventoryDeductionItemRequest> items
) {
}
