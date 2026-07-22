package com.osheeep.server.dinner.record.dto;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record RecordDetailResponse(
        Long id,
        LocalDate recordDate,
        HouseholdActorResponse completedBy,
        Instant completedAt,
        List<RecordDishResponse> dishes
) {
}
