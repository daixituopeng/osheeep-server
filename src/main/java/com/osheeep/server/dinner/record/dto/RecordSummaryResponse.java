package com.osheeep.server.dinner.record.dto;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import java.time.Instant;
import java.time.LocalDate;

public record RecordSummaryResponse(
        Long id,
        LocalDate recordDate,
        HouseholdActorResponse completedBy,
        Instant completedAt,
        int dishCount
) {
}
