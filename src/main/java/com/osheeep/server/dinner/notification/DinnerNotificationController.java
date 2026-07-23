package com.osheeep.server.dinner.notification;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.notification.dto.DinnerNotificationPageResponse;
import com.osheeep.server.dinner.notification.dto.DinnerNotificationReadAllResponse;
import com.osheeep.server.dinner.notification.dto.DinnerNotificationUnreadCountResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/dinner/notifications")
public class DinnerNotificationController {

    private final DinnerNotificationService service;

    public DinnerNotificationController(DinnerNotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<DinnerNotificationPageResponse> page(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return ApiResponse.ok(service.page(currentUser.id(), beforeId, limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<DinnerNotificationUnreadCountResponse> unreadCount(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(
                new DinnerNotificationUnreadCountResponse(
                        service.unreadCount(currentUser.id())));
    }

    @PutMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable @Positive Long notificationId
    ) {
        service.markRead(currentUser.id(), notificationId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/read-all")
    public ApiResponse<DinnerNotificationReadAllResponse> markAllRead(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(
                new DinnerNotificationReadAllResponse(
                        service.markAllRead(currentUser.id())));
    }
}
