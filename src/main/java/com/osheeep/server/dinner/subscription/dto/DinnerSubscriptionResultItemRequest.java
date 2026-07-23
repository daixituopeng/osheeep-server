package com.osheeep.server.dinner.subscription.dto;

import jakarta.validation.constraints.NotBlank;

public record DinnerSubscriptionResultItemRequest(
        @NotBlank String scenario,
        @NotBlank String outcome
) {
}
