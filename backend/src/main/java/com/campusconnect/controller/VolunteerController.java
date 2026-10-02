package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.dto.request.VolunteerAddRequest;
import com.campusconnect.dto.request.VolunteerAssignRequest;
import com.campusconnect.dto.request.VolunteerProfileUpdateRequest;
import com.campusconnect.dto.request.VolunteerScanRequest;
import com.campusconnect.dto.request.VolunteerTaskCompleteRequest;
import com.campusconnect.dto.request.VolunteerTaskCreateRequest;
import com.campusconnect.dto.response.VolunteerAchievementResponse;
import com.campusconnect.dto.response.VolunteerAssignmentResponse;
import com.campusconnect.dto.response.VolunteerAttendanceResponse;
import com.campusconnect.dto.response.VolunteerDashboardResponse;
import com.campusconnect.dto.response.VolunteerHoursResponse;
import com.campusconnect.dto.response.VolunteerResponse;
import com.campusconnect.dto.response.VolunteerScanResponse;
import com.campusconnect.dto.response.VolunteerScheduleItemResponse;
import com.campusconnect.dto.response.VolunteerTaskResponse;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.VolunteerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Volunteer role endpoints.
 *
 * <p>The {@code /me/**} surface is the volunteer's own view and is gated to the
 * VOLUNTEER role (defence-in-depth on top of per-record ownership checks in the
 * service). The coordinator/member management surface under {@code /clubs},
 * {@code /events} and {@code /{id}} enforces club coordinator/admin (or active
 * member) rights inside the service via {@code ClubAccess}.
 */
@RestController
@RequestMapping("/api/volunteers")
@RequiredArgsConstructor
@Tag(name = "Volunteers", description = "Volunteer profiles, assignments, tasks, attendance, hours and QR check-in")
public class VolunteerController {

    private final VolunteerService volunteerService;

    // ---------------------------- self-service ----------------------------

    @GetMapping("/me")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "My volunteer profiles across clubs")
    public ApiResponse<List<VolunteerResponse>> myProfiles(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(volunteerService.myProfiles(principal.getId()));
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update my volunteer skills and availability")
    public ApiResponse<VolunteerResponse> updateMyProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                          @Valid @RequestBody VolunteerProfileUpdateRequest request) {
        return ApiResponse.success("Profile updated",
                volunteerService.updateMyProfile(principal.getId(), request));
    }

    @GetMapping("/me/dashboard")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "My volunteer dashboard summary")
    public ApiResponse<VolunteerDashboardResponse> dashboard(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(volunteerService.dashboard(principal.getId()));
    }

    @GetMapping("/me/events")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Events I'm assigned to")
    public ApiResponse<List<VolunteerAssignmentResponse>> myEvents(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(volunteerService.myEvents(principal.getId()));
    }

    @PutMapping("/me/events/{assignmentId}/accept")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Accept an event assignment")
    public ApiResponse<VolunteerAssignmentResponse> acceptAssignment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long assignmentId) {
        return ApiResponse.success("Assignment accepted",
                volunteerService.acceptAssignment(principal.getId(), assignmentId));
    }

    @GetMapping("/me/tasks")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "My volunteer tasks")
    public ApiResponse<List<VolunteerTaskResponse>> myTasks(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(volunteerService.myTasks(principal.getId()));
    }

    @GetMapping("/me/tasks/{id}")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "A single task of mine")
    public ApiResponse<VolunteerTaskResponse> myTask(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long id) {
        return ApiResponse.success(volunteerService.myTask(principal.getId(), id));
    }

    @PutMapping("/me/tasks/{id}/start")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Start a task")
    public ApiResponse<VolunteerTaskResponse> startTask(@AuthenticationPrincipal UserPrincipal principal,
                                                        @PathVariable Long id) {
        return ApiResponse.success("Task started", volunteerService.startTask(principal.getId(), id));
    }

    @PutMapping("/me/tasks/{id}/complete")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Complete a task with optional notes")
    public ApiResponse<VolunteerTaskResponse> completeTask(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long id,
                                                           @Valid @RequestBody(required = false)
                                                           VolunteerTaskCompleteRequest request) {
        return ApiResponse.success("Task completed",
                volunteerService.completeTask(principal.getId(), id, request));
    }

    @GetMapping("/me/schedule")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "My shift schedule")
    public ApiResponse<List<VolunteerScheduleItemResponse>> mySchedule(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(volunteerService.mySchedule(principal.getId()));
    }

    @PostMapping("/me/attendance/check-in")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Mark my own volunteer check-in for an event")
    public ApiResponse<VolunteerAttendanceResponse> checkIn(@AuthenticationPrincipal UserPrincipal principal,
                                                            @org.springframework.web.bind.annotation.RequestParam
                                                            Long eventId) {
        return ApiResponse.success("Checked in", volunteerService.checkIn(principal.getId(), eventId));
    }

    @PostMapping("/me/attendance/check-out")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Mark my own volunteer check-out for an event")
    public ApiResponse<VolunteerAttendanceResponse> checkOut(@AuthenticationPrincipal UserPrincipal principal,
                                                             @org.springframework.web.bind.annotation.RequestParam
                                                             Long eventId) {
        return ApiResponse.success("Checked out", volunteerService.checkOut(principal.getId(), eventId));
    }

    @GetMapping("/me/attendance")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "My volunteer attendance history")
    public ApiResponse<List<VolunteerAttendanceResponse>> myAttendance(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(volunteerService.myAttendance(principal.getId()));
    }

    @GetMapping("/me/hours")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "My volunteer-hours summary")
    public ApiResponse<VolunteerHoursResponse> myHours(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(volunteerService.myHours(principal.getId()));
    }

    @GetMapping("/me/achievements")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "My volunteer achievements/badges")
    public ApiResponse<List<VolunteerAchievementResponse>> myAchievements(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(volunteerService.myAchievements(principal.getId()));
    }

    @PostMapping("/scan")
    @PreAuthorize("hasRole('VOLUNTEER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Scan a participant ticket QR to check them in (requires check-in duty)")
    public ApiResponse<VolunteerScanResponse> scan(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody VolunteerScanRequest request) {
        VolunteerScanResponse result = volunteerService.scan(principal.getId(), request);
        return ApiResponse.success(result.message(), result);
    }

    // ----------------------- coordinator / member -----------------------

    @GetMapping("/clubs/{clubId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List a club's volunteers (coordinator or admin)")
    public ApiResponse<List<VolunteerResponse>> clubVolunteers(@AuthenticationPrincipal UserPrincipal principal,
                                                               @PathVariable Long clubId) {
        return ApiResponse.success(volunteerService.clubVolunteers(principal.getId(), clubId));
    }

    @PostMapping("/clubs/{clubId}")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Add a user (by email) as a volunteer for a club (active member, coordinator or admin)")
    public ApiResponse<VolunteerResponse> addVolunteer(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable Long clubId,
                                                       @Valid @RequestBody VolunteerAddRequest request) {
        return ApiResponse.success("Volunteer added",
                volunteerService.addVolunteer(principal.getId(), clubId, request));
    }

    @GetMapping("/{volunteerId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get a single volunteer (coordinator or admin)")
    public ApiResponse<VolunteerResponse> getVolunteer(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable Long volunteerId) {
        return ApiResponse.success(volunteerService.getVolunteer(principal.getId(), volunteerId));
    }

    @PutMapping("/{volunteerId}/approve")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Approve a volunteer (coordinator or admin)")
    public ApiResponse<VolunteerResponse> approve(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long volunteerId) {
        return ApiResponse.success("Volunteer approved",
                volunteerService.approveVolunteer(principal.getId(), volunteerId));
    }

    @PutMapping("/{volunteerId}/reject")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Reject/deactivate a volunteer (coordinator or admin)")
    public ApiResponse<VolunteerResponse> reject(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable Long volunteerId) {
        return ApiResponse.success("Volunteer rejected",
                volunteerService.rejectVolunteer(principal.getId(), volunteerId));
    }

    @GetMapping("/events/{eventId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Volunteers assigned to an event (coordinator/admin or active member)")
    public ApiResponse<List<VolunteerAssignmentResponse>> eventVolunteers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long eventId) {
        return ApiResponse.success(volunteerService.eventVolunteers(principal.getId(), eventId));
    }

    @PostMapping("/events/{eventId}/assign")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Assign a volunteer to an event (coordinator or admin)")
    public ApiResponse<VolunteerAssignmentResponse> assign(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long eventId,
                                                           @Valid @RequestBody VolunteerAssignRequest request) {
        return ApiResponse.success("Volunteer assigned",
                volunteerService.assignToEvent(principal.getId(), eventId, request));
    }

    @PutMapping("/assignments/{assignmentId}/cancel")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Cancel a volunteer assignment (coordinator or admin)")
    public ApiResponse<VolunteerAssignmentResponse> cancelAssignment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long assignmentId) {
        return ApiResponse.success("Assignment cancelled",
                volunteerService.cancelAssignment(principal.getId(), assignmentId));
    }

    @GetMapping("/events/{eventId}/tasks")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Volunteer tasks for an event (coordinator/admin or active member)")
    public ApiResponse<List<VolunteerTaskResponse>> eventTasks(@AuthenticationPrincipal UserPrincipal principal,
                                                               @PathVariable Long eventId) {
        return ApiResponse.success(volunteerService.eventTasks(principal.getId(), eventId));
    }

    @PostMapping("/events/{eventId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a volunteer task for an event (coordinator/admin or active member)")
    public ApiResponse<VolunteerTaskResponse> createTask(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable Long eventId,
                                                         @Valid @RequestBody VolunteerTaskCreateRequest request) {
        return ApiResponse.success("Task created",
                volunteerService.createTask(principal.getId(), eventId, request));
    }

    @GetMapping("/events/{eventId}/attendance")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Volunteer attendance for an event (coordinator/admin or active member)")
    public ApiResponse<List<VolunteerAttendanceResponse>> eventVolunteerAttendance(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long eventId) {
        return ApiResponse.success(volunteerService.eventVolunteerAttendance(principal.getId(), eventId));
    }
}
