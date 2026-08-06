package com.osheeep.server.dinner.shopping.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CompleteShoppingRequest(
        @NotEmpty @Size(max = 100) List<@Valid CompleteShoppingItemRequest> items
) {
}
