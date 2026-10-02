package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.dto.request.AnnouncementRequest;
import com.campusconnect.dto.request.AnnouncementUpdateRequest;
import com.campusconnect.dto.response.AnnouncementResponse;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.AnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
@Tag(name = "Announcements", description = "Club, event and campus-wide announcements")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Post an announcement (coordinator; scope-dependent authorization)")
    public ApiResponse<AnnouncementResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                    @Valid @RequestBody AnnouncementRequest request) {
        return ApiResponse.success("Announcement posted",
                announcementService.create(principal.getId(), request));
    }

    @PutMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Edit an announcement (author or relevant coordinator)")
    public ApiResponse<AnnouncementResponse> update(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody AnnouncementUpdateRequest request) {
        return ApiResponse.success("Announcement updated",
                announcementService.update(principal.getId(), id, request));
    }

    @DeleteMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete an announcement (author or relevant coordinator)")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable Long id) {
        announcementService.delete(principal.getId(), id);
        return ApiResponse.message("Announcement deleted");
    }

    @GetMapping
    @Operation(summary = "Latest announcements across the platform")
    public ApiResponse<List<AnnouncementResponse>> feed() {
        return ApiResponse.success(announcementService.feed());
    }

    @GetMapping("/general")
    @Operation(summary = "Campus-wide (general) announcements")
    public ApiResponse<List<AnnouncementResponse>> general() {
        return ApiResponse.success(announcementService.listGeneral());
    }

    @GetMapping("/club/{clubId}")
    @Operation(summary = "Announcements for a club")
    public ApiResponse<List<AnnouncementResponse>> byClub(@PathVariable Long clubId) {
        return ApiResponse.success(announcementService.listByClub(clubId));
    }

    @GetMapping("/event/{eventId}")
    @Operation(summary = "Announcements for an event")
    public ApiResponse<List<AnnouncementResponse>> byEvent(@PathVariable Long eventId) {
        return ApiResponse.success(announcementService.listByEvent(eventId));
    }
}
