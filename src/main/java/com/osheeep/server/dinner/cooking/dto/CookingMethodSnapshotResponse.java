package com.osheeep.server.dinner.cooking.dto;

import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import java.util.List;

public record CookingMethodSnapshotResponse(
        Long id,
        String name,
        String cookingStyle,
        Integer estimatedMinutes,
        List<RecordMethodStepSnapshotResponse> steps
) {
    public CookingMethodSnapshotResponse {
        steps = List.copyOf(steps);
    }
}
