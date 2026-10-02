package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.common.PageRequests;
import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.EventRequest;
import com.campusconnect.dto.request.EventScheduleRequest;
import com.campusconnect.dto.request.EventSearchCriteria;
import com.campusconnect.dto.response.EventResponse;
import com.campusconnect.dto.response.EventScheduleResponse;
import com.campusconnect.dto.response.EventSummaryResponse;
import com.campusconnect.entity.enums.EventMode;
import com.campusconnect.entity.enums.EventStatus;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.EventService;
import com.campusconnect.service.calendar.CalendarExport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "Events", description = "Event discovery, management and schedules")
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final ClubAccess clubAccess;

    @Operation(summary = "Search events (public). Drafts are only returned to authenticated coordinators of the club.")
    @GetMapping
    public ApiResponse<PageResponse<EventSummaryResponse>> search(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) EventMode mode,
            @RequestParam(required = false) EventStatus status,
            @RequestParam(required = false) Long clubId,
            @RequestParam(required = false) Boolean paid,
            @RequestParam(required = false) Boolean team,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "false") boolean includeDrafts,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {

        boolean includeUnpublished = includeDrafts
                && principal != null
                && clubId != null
                && clubAccess.isActiveMember(clubId, principal.getId());

        Long viewerId = principal != null ? principal.getId() : null;
        EventSearchCriteria criteria =
                new EventSearchCriteria(q, category, mode, status, clubId, paid, team, featured, from, to);
        return ApiResponse.success(eventService.search(criteria, includeUnpublished, viewerId,
                PageRequests.of(page, size, Sort.by("startDateTime").ascending())));
    }

    @Operation(summary = "List featured events (public)")
    @GetMapping("/featured")
    public ApiResponse<List<EventSummaryResponse>> featured(@AuthenticationPrincipal UserPrincipal principal) {
        Long viewerId = principal != null ? principal.getId() : null;
        return ApiResponse.success(eventService.featured(viewerId));
    }

    @Operation(summary = "Get an event by id (public)")
    @GetMapping("/{id}")
    public ApiResponse<EventResponse> getById(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable Long id) {
        Long viewerId = principal != null ? principal.getId() : null;
        return ApiResponse.success(eventService.getById(id, viewerId));
    }

    @Operation(summary = "Download an event as an iCalendar (.ics) file (public)")
    @GetMapping("/{id}/calendar.ics")
    public ResponseEntity<byte[]> calendar(@PathVariable Long id) {
        CalendarExport export = eventService.exportCalendar(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName() + "\"")
                .contentType(MediaType.parseMediaType("text/calendar; charset=UTF-8"))
                .body(export.content());
    }

    @Operation(summary = "Get an event's schedule (public)")
    @GetMapping("/{id}/schedule")
    public ApiResponse<List<EventScheduleResponse>> schedule(@PathVariable Long id) {
        return ApiResponse.success(eventService.listSchedules(id));
    }

    @Operation(summary = "Create an event (active club members)")
    @PostMapping
    public ResponseEntity<ApiResponse<EventResponse>> create(@AuthenticationPrincipal UserPrincipal principal,
                                                            @Valid @RequestBody EventRequest request) {
        EventResponse created = eventService.create(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Event created as draft.", created));
    }

    @Operation(summary = "Update an event (active club members)")
    @PutMapping("/{id}")
    public ApiResponse<EventResponse> update(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable Long id,
                                             @Valid @RequestBody EventRequest request) {
        return ApiResponse.success("Event updated.", eventService.update(principal.getId(), id, request));
    }

    @Operation(summary = "Publish an event (club coordinator)")
    @PostMapping("/{id}/publish")
    public ApiResponse<EventResponse> publish(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return ApiResponse.success("Event published.", eventService.publish(principal.getId(), id));
    }

    @Operation(summary = "Change an event's status (club coordinator)")
    @PatchMapping("/{id}/status")
    public ApiResponse<EventResponse> updateStatus(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long id,
                                                   @RequestParam EventStatus status) {
        return ApiResponse.success("Event status updated.", eventService.updateStatus(principal.getId(), id, status));
    }

    @Operation(summary = "Delete a draft event (club coordinator)")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        eventService.delete(principal.getId(), id);
        return ApiResponse.message("Event deleted.");
    }

    @Operation(summary = "Add a schedule item to an event (active club members)")
    @PostMapping("/{id}/schedule")
    public ResponseEntity<ApiResponse<EventScheduleResponse>> addSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody EventScheduleRequest request) {
        EventScheduleResponse created = eventService.addSchedule(principal.getId(), id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Schedule item added.", created));
    }

    @Operation(summary = "Update a schedule item (active club members)")
    @PutMapping("/{id}/schedule/{scheduleId}")
    public ApiResponse<EventScheduleResponse> updateSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @PathVariable Long scheduleId,
            @Valid @RequestBody EventScheduleRequest request) {
        return ApiResponse.success("Schedule item updated.",
                eventService.updateSchedule(principal.getId(), id, scheduleId, request));
    }

    @Operation(summary = "Delete a schedule item (active club members)")
    @DeleteMapping("/{id}/schedule/{scheduleId}")
    public ApiResponse<Void> deleteSchedule(@AuthenticationPrincipal UserPrincipal principal,
                                            @PathVariable Long id,
                                            @PathVariable Long scheduleId) {
        eventService.deleteSchedule(principal.getId(), id, scheduleId);
        return ApiResponse.message("Schedule item deleted.");
    }

    @Operation(summary = "Save (bookmark) an event")
    @PostMapping("/{id}/save")
    public ApiResponse<Void> save(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        eventService.save(principal.getId(), id);
        return ApiResponse.message("Event saved.");
    }

    @Operation(summary = "Remove an event from your saved list")
    @DeleteMapping("/{id}/save")
    public ApiResponse<Void> unsave(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        eventService.unsave(principal.getId(), id);
        return ApiResponse.message("Event removed from your saved list.");
    }

    @Operation(summary = "List events the current user has saved")
    @GetMapping("/me/saved")
    public ApiResponse<List<EventSummaryResponse>> mySaved(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(eventService.mySavedEvents(principal.getId()));
    }
}
