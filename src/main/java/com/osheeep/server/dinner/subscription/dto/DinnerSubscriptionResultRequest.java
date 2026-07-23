package com.osheeep.server.dinner.subscription.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record DinnerSubscriptionResultRequest(
        @NotNull UUID requestId,
        @NotBlank String action,
        @NotNull @Size(min = 1, max = 5) List<@Valid DinnerSubscriptionResultItemRequest> results
) {
}
