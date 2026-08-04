package com.osheeep.server.dinner.menu.dto;

import java.time.LocalDate;
import java.util.List;

public record WeekMenuResponse(
        LocalDate startDate,
        LocalDate endDate,
        List<TodayMenuResponse> menus
) {
}
