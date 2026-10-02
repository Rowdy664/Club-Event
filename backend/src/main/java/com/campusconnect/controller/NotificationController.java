package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.common.PageRequests;
import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.NotificationPreferenceRequest;
import com.campusconnect.dto.response.NotificationPreferenceResponse;
import com.campusconnect.dto.response.NotificationResponse;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Notifications", description = "In-app notifications (also pushed live over WebSocket)")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/me")
    @Operation(summary = "List my notifications (paged, newest first)")
    public ApiResponse<PageResponse<NotificationResponse>> myNotifications(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(notificationService.myNotifications(
                principal.getId(),
                PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/me/unread")
    @Operation(summary = "List my unread notifications")
    public ApiResponse<List<NotificationResponse>> myUnread(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(notificationService.myUnread(principal.getId()));
    }

    @GetMapping("/me/unread-count")
    @Operation(summary = "Count my unread notifications")
    public ApiResponse<Map<String, Long>> unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(Map.of("count", notificationService.unreadCount(principal.getId())));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark a notification as read")
    public ApiResponse<Void> markRead(@AuthenticationPrincipal UserPrincipal principal,
                                      @PathVariable Long id) {
        notificationService.markRead(principal.getId(), id);
        return ApiResponse.message("Notification marked as read");
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all my notifications as read")
    public ApiResponse<Void> markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markAllRead(principal.getId());
        return ApiResponse.message("All notifications marked as read");
    }

    @PostMapping("/{id}/unread")
    @Operation(summary = "Mark a notification as unread")
    public ApiResponse<Void> markUnread(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable Long id) {
        notificationService.markUnread(principal.getId(), id);
        return ApiResponse.message("Notification marked as unread");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete one of my notifications")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable Long id) {
        notificationService.delete(principal.getId(), id);
        return ApiResponse.message("Notification deleted");
    }

    @DeleteMapping("/me")
    @Operation(summary = "Delete all of my notifications (clear inbox)")
    public ApiResponse<Void> deleteAll(@AuthenticationPrincipal UserPrincipal principal) {
        notificationService.deleteAll(principal.getId());
        return ApiResponse.message("All notifications cleared");
    }

    @GetMapping("/preferences")
    @Operation(summary = "Get my email notification preferences")
    public ApiResponse<NotificationPreferenceResponse> getPreferences(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(notificationService.getPreferences(principal.getId()));
    }

    @PutMapping("/preferences")
    @Operation(summary = "Update my email notification preferences")
    public ApiResponse<NotificationPreferenceResponse> updatePreferences(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody NotificationPreferenceRequest request) {
        return ApiResponse.success("Notification preferences updated.",
                notificationService.updatePreferences(principal.getId(), request));
    }

    @PostMapping("/unsubscribe")
    @SecurityRequirements
    @Operation(summary = "One-click unsubscribe from all emails using a token from an email link (public)")
    public ApiResponse<Void> unsubscribe(@RequestParam String token) {
        notificationService.unsubscribe(token);
        return ApiResponse.message("You have been unsubscribed from CampusConnect emails.");
    }
}
