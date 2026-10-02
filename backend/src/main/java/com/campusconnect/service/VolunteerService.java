package com.campusconnect.service;

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

import java.util.List;

/**
 * Volunteer-facing operations plus the coordinator/member management surface.
 *
 * <p>Every {@code my*} method is scoped to the acting user's own volunteer
 * profile(s); event-scoped reads/writes additionally verify an assignment. The
 * coordinator/member methods enforce club coordinator (or admin) rights, or an
 * active member of the owning club for the delegated task/attendance actions.
 */
public interface VolunteerService {

    // ---- volunteer self-service ----

    /** The acting user's volunteer profiles across all clubs. */
    List<VolunteerResponse> myProfiles(Long userId);

    VolunteerResponse updateMyProfile(Long userId, VolunteerProfileUpdateRequest request);

    VolunteerDashboardResponse dashboard(Long userId);

    List<VolunteerAssignmentResponse> myEvents(Long userId);

    /** Accept an assignment offered to the acting volunteer. */
    VolunteerAssignmentResponse acceptAssignment(Long userId, Long assignmentId);

    List<VolunteerTaskResponse> myTasks(Long userId);

    VolunteerTaskResponse myTask(Long userId, Long taskId);

    VolunteerTaskResponse startTask(Long userId, Long taskId);

    VolunteerTaskResponse completeTask(Long userId, Long taskId, VolunteerTaskCompleteRequest request);

    List<VolunteerScheduleItemResponse> mySchedule(Long userId);

    VolunteerAttendanceResponse checkIn(Long userId, Long eventId);

    VolunteerAttendanceResponse checkOut(Long userId, Long eventId);

    List<VolunteerAttendanceResponse> myAttendance(Long userId);

    VolunteerHoursResponse myHours(Long userId);

    List<VolunteerAchievementResponse> myAchievements(Long userId);

    /** Participant check-in performed by a volunteer with check-in duty (QR scan). */
    VolunteerScanResponse scan(Long userId, VolunteerScanRequest request);

    // ---- coordinator / member management ----

    /**
     * Add an existing user (found by email) as an ACTIVE volunteer for a club.
     * Coordinator/admin only — this is the sole way a volunteer profile is created.
     */
    VolunteerResponse addVolunteer(Long actingUserId, Long clubId, VolunteerAddRequest request);

    /** Volunteers for a club (coordinator or admin). */
    List<VolunteerResponse> clubVolunteers(Long actingUserId, Long clubId);

    VolunteerResponse getVolunteer(Long actingUserId, Long volunteerId);

    VolunteerResponse approveVolunteer(Long actingUserId, Long volunteerId);

    VolunteerResponse rejectVolunteer(Long actingUserId, Long volunteerId);

    /** Volunteers assigned to an event (coordinator/admin, or active member of the club). */
    List<VolunteerAssignmentResponse> eventVolunteers(Long actingUserId, Long eventId);

    VolunteerAssignmentResponse assignToEvent(Long actingUserId, Long eventId, VolunteerAssignRequest request);

    VolunteerAssignmentResponse cancelAssignment(Long actingUserId, Long assignmentId);

    List<VolunteerTaskResponse> eventTasks(Long actingUserId, Long eventId);

    VolunteerTaskResponse createTask(Long actingUserId, Long eventId, VolunteerTaskCreateRequest request);

    List<VolunteerAttendanceResponse> eventVolunteerAttendance(Long actingUserId, Long eventId);
}
