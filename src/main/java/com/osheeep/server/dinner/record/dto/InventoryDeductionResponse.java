package com.osheeep.server.dinner.record.dto;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import java.time.Instant;
import java.util.List;

public record InventoryDeductionResponse(
        Long recordId,
        String status,
        HouseholdActorResponse handledBy,
        Instant handledAt,
        List<InventoryDeductionProposalItemResponse> proposalItems,
        List<InventoryDeductionAppliedItemResponse> appliedItems
) {
}
