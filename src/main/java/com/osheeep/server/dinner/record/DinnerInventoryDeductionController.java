package com.osheeep.server.dinner.record;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.record.dto.HandleInventoryDeductionRequest;
import com.osheeep.server.dinner.record.dto.InventoryDeductionResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/records/{recordId}/inventory-deduction")
public class DinnerInventoryDeductionController {

    private final DinnerInventoryDeductionService deductionService;

    public DinnerInventoryDeductionController(
            DinnerInventoryDeductionService deductionService
    ) {
        this.deductionService = deductionService;
    }

    @GetMapping
    public ApiResponse<InventoryDeductionResponse> get(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long recordId
    ) {
        return ApiResponse.ok(deductionService.get(currentUser.id(), recordId));
    }

    @PostMapping
    public ApiResponse<InventoryDeductionResponse> handle(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long recordId,
            @Valid @RequestBody HandleInventoryDeductionRequest request
    ) {
        return ApiResponse.ok(deductionService.handle(currentUser.id(), recordId, request));
    }
}
