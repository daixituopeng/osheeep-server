package com.osheeep.server.dinner.subscription;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionConfigResponse;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionResultRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/subscriptions")
public class DinnerSubscriptionController {

    private final DinnerSubscriptionService service;

    public DinnerSubscriptionController(DinnerSubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public ApiResponse<DinnerSubscriptionConfigResponse> config(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(service.config(currentUser.id()));
    }

    @PostMapping("/results")
    public ApiResponse<Void> results(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody DinnerSubscriptionResultRequest request
    ) {
        service.recordResults(currentUser.id(), request);
        return ApiResponse.ok(null);
    }
}
