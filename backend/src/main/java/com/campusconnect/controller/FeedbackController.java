package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.dto.request.FeedbackRequest;
import com.campusconnect.dto.response.FeedbackResponse;
import com.campusconnect.dto.response.FeedbackSummary;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@Tag(name = "Feedback", description = "Event feedback and ratings")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Submit or update my feedback for an event")
    public ApiResponse<FeedbackResponse> submit(@AuthenticationPrincipal UserPrincipal principal,
                                                @Valid @RequestBody FeedbackRequest request) {
        return ApiResponse.success("Feedback saved", feedbackService.submit(principal.getId(), request));
    }

    @GetMapping("/me/event/{eventId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get my feedback for an event")
    public ApiResponse<FeedbackResponse> myFeedback(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long eventId) {
        return ApiResponse.success(feedbackService.myFeedbackForEvent(principal.getId(), eventId));
    }

    @GetMapping("/event/{eventId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List individual feedback for an event (club coordinator only)")
    public ApiResponse<List<FeedbackResponse>> eventFeedback(@AuthenticationPrincipal UserPrincipal principal,
                                                             @PathVariable Long eventId) {
        return ApiResponse.success(feedbackService.eventFeedback(principal.getId(), eventId));
    }

    @GetMapping("/event/{eventId}/summary")
    @Operation(summary = "Get the aggregate feedback summary for an event (public)")
    public ApiResponse<FeedbackSummary> summary(@PathVariable Long eventId) {
        return ApiResponse.success(feedbackService.eventSummary(eventId));
    }

    @DeleteMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete a feedback entry (event's club coordinator only)")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable Long id) {
        feedbackService.delete(principal.getId(), id);
        return ApiResponse.message("Feedback deleted");
    }
}
