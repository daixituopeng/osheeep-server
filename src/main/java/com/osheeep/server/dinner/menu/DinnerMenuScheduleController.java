package com.osheeep.server.dinner.menu;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.menu.dto.TodayMenuResponse;
import com.osheeep.server.dinner.menu.dto.UpdateSelectionsRequest;
import com.osheeep.server.dinner.menu.dto.WeekMenuResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/menus")
public class DinnerMenuScheduleController {

    private final DinnerMenuService menuService;

    public DinnerMenuScheduleController(DinnerMenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/week")
    public ApiResponse<WeekMenuResponse> week(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start
    ) {
        return ApiResponse.ok(menuService.week(currentUser.id(), start));
    }

    @GetMapping("/{date:\\d{4}-\\d{2}-\\d{2}}")
    public ApiResponse<TodayMenuResponse> scheduled(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ApiResponse.ok(menuService.scheduled(currentUser.id(), date));
    }

    @PutMapping("/{date:\\d{4}-\\d{2}-\\d{2}}/selections")
    public ApiResponse<TodayMenuResponse> updateSelections(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody UpdateSelectionsRequest request
    ) {
        if ((request.recipeIds() == null) == (request.selections() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (request.selections() != null) {
            return ApiResponse.ok(menuService.updateScheduledMethodSelections(
                    currentUser.id(), date, request.selections(), request.version()));
        }
        return ApiResponse.ok(menuService.updateScheduledSelections(
                currentUser.id(), date, request.recipeIds(), request.version()));
    }
}
