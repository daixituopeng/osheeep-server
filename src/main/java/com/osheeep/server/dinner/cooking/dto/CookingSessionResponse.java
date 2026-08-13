package com.osheeep.server.dinner.cooking.dto;

import java.time.LocalDate;
import java.util.List;

public record CookingSessionResponse(
        Long menuId,
        LocalDate menuDate,
        String status,
        Long version,
        Long recordId,
        List<CookingDishResponse> dishes
) {
    public CookingSessionResponse {
        dishes = List.copyOf(dishes);
    }
}
