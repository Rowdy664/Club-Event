package com.campusconnect.service.impl;

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
import com.campusconnect.entity.Attendance;
import com.campusconnect.entity.Club;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.Registration;
import com.campusconnect.entity.User;
import com.campusconnect.entity.Volunteer;
import com.campusconnect.entity.VolunteerAssignment;
import com.campusconnect.entity.VolunteerAttendance;
import com.campusconnect.entity.VolunteerTask;
import com.campusconnect.entity.enums.AttendanceMethod;
import com.campusconnect.entity.enums.NotificationType;
import com.campusconnect.entity.enums.RegistrationStatus;
import com.campusconnect.entity.enums.Role;
import com.campusconnect.entity.enums.VolunteerAssignmentStatus;
import com.campusconnect.entity.enums.VolunteerAttendanceStatus;
import com.campusconnect.entity.enums.VolunteerStatus;
import com.campusconnect.entity.enums.VolunteerTaskPriority;
import com.campusconnect.entity.enums.VolunteerTaskStatus;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ConflictException;
import com.campusconnect.exception.ForbiddenException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.repository.AttendanceRepository;
import com.campusconnect.repository.ClubRepository;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.RegistrationRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.repository.VolunteerAssignmentRepository;
import com.campusconnect.repository.VolunteerAttendanceRepository;
import com.campusconnect.repository.VolunteerRepository;
import com.campusconnect.repository.VolunteerTaskRepository;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.service.VolunteerService;
import com.campusconnect.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VolunteerServiceImpl implements VolunteerService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final VolunteerRepository volunteerRepository;
    private final VolunteerAssignmentRepository assignmentRepository;
    private final VolunteerTaskRepository taskRepository;
    private final VolunteerAttendanceRepository attendanceRepository;
    private final ClubRepository clubRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final RegistrationRepository registrationRepository;
    private final AttendanceRepository participantAttendanceRepository;
    private final ClubAccess clubAccess;
    private final NotificationService notificationService;

    // ============================ self-service ============================

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerResponse> myProfiles(Long userId) {
        return volunteerRepository.findByUserId(userId).stream()
                .map(this::toVolunteerResponse)
                .toList();
    }

    @Override
    @Transactional
    public VolunteerResponse updateMyProfile(Long userId, VolunteerProfileUpdateRequest request) {
        Volunteer volunteer = requireOwnPrimaryProfile(userId);
        if (request.skills() != null) {
            volunteer.setSkills(request.skills());
        }
        if (request.availability() != null) {
            volunteer.setAvailability(request.availability());
        }
        return toVolunteerResponse(volunteerRepository.save(volunteer));
    }

    @Override
    @Transactional(readOnly = true)
    public VolunteerDashboardResponse dashboard(Long userId) {
        User user = requireUser(userId);
        List<Volunteer> profiles = volunteerRepository.findByUserId(userId);
        if (profiles.isEmpty()) {
            return new VolunteerDashboardResponse(user.getFullName(), false, null,
                    0, 0, 0, 0, 0d, 0, null, List.of(), List.of());
        }
        List<Long> volunteerIds = profiles.stream().map(Volunteer::getId).toList();

        List<VolunteerAssignment> assignments = assignmentRepository.findByVolunteerIdIn(volunteerIds);
        List<VolunteerTask> tasks = taskRepository.findByVolunteerIdIn(volunteerIds);

        long pending = tasks.stream().filter(t -> t.getStatus() == VolunteerTaskStatus.PENDING).count();
        long inProgress = tasks.stream().filter(t -> t.getStatus() == VolunteerTaskStatus.IN_PROGRESS).count();
        long completed = tasks.stream().filter(t -> t.getStatus() == VolunteerTaskStatus.COMPLETED).count();
        double totalHours = profiles.stream().mapToDouble(Volunteer::getTotalHours).sum();

        LocalDate today = LocalDate.now(ZONE);
        VolunteerAssignmentResponse todays = assignments.stream()
                .filter(a -> a.getEvent() != null && a.getEvent().getStartDateTime() != null
                        && a.getEvent().getStartDateTime().toLocalDate().isEqual(today))
                .sorted(Comparator.comparing(a -> a.getEvent().getStartDateTime()))
                .map(this::toAssignmentResponse)
                .findFirst()
                .orElse(null);

        List<VolunteerAssignmentResponse> upcoming = assignments.stream()
                .filter(a -> a.getEvent() != null && a.getEvent().getStartDateTime() != null
                        && a.getEvent().getStartDateTime().isAfter(today.atStartOfDay()))
                .sorted(Comparator.comparing(a -> a.getEvent().getStartDateTime()))
                .limit(5)
                .map(this::toAssignmentResponse)
                .toList();

        List<VolunteerTaskResponse> recentTasks = tasks.stream()
                .sorted(Comparator.comparing(VolunteerTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(this::toTaskResponse)
                .toList();

        int attendancePct = attendancePercentage(volunteerIds);
        String status = profiles.stream().anyMatch(p -> p.getStatus() == VolunteerStatus.ACTIVE)
                ? VolunteerStatus.ACTIVE.name()
                : profiles.get(0).getStatus().name();

        return new VolunteerDashboardResponse(user.getFullName(), true, status,
                (int) assignments.stream().map(a -> a.getEvent().getId()).distinct().count(),
                pending, inProgress, completed, totalHours, attendancePct,
                todays, upcoming, recentTasks);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerAssignmentResponse> myEvents(Long userId) {
        List<Long> ids = myVolunteerIds(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return assignmentRepository.findByVolunteerIdIn(ids).stream()
                .sorted(Comparator.comparing((VolunteerAssignment a) ->
                        a.getEvent() != null ? a.getEvent().getStartDateTime() : null,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toAssignmentResponse)
                .toList();
    }

    @Override
    @Transactional
    public VolunteerAssignmentResponse acceptAssignment(Long userId, Long assignmentId) {
        VolunteerAssignment assignment = requireOwnAssignment(userId, assignmentId);
        if (assignment.getStatus() == VolunteerAssignmentStatus.CANCELLED) {
            throw new BadRequestException("This assignment has been cancelled.");
        }
        if (assignment.getStatus() == VolunteerAssignmentStatus.ASSIGNED) {
            assignment.setStatus(VolunteerAssignmentStatus.ACCEPTED);
            assignment = assignmentRepository.save(assignment);
        }
        return toAssignmentResponse(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerTaskResponse> myTasks(Long userId) {
        List<Long> ids = myVolunteerIds(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return taskRepository.findByVolunteerIdIn(ids).stream()
                .sorted(Comparator.comparing(VolunteerTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toTaskResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VolunteerTaskResponse myTask(Long userId, Long taskId) {
        return toTaskResponse(requireOwnTask(userId, taskId));
    }

    @Override
    @Transactional
    public VolunteerTaskResponse startTask(Long userId, Long taskId) {
        VolunteerTask task = requireOwnTask(userId, taskId);
        if (task.getStatus() == VolunteerTaskStatus.COMPLETED) {
            throw new BadRequestException("This task is already completed.");
        }
        if (task.getStatus() == VolunteerTaskStatus.CANCELLED) {
            throw new BadRequestException("This task has been cancelled.");
        }
        task.setStatus(VolunteerTaskStatus.IN_PROGRESS);
        if (task.getStartedAt() == null) {
            task.setStartedAt(Instant.now());
        }
        return toTaskResponse(taskRepository.save(task));
    }

    @Override
    @Transactional
    public VolunteerTaskResponse completeTask(Long userId, Long taskId, VolunteerTaskCompleteRequest request) {
        VolunteerTask task = requireOwnTask(userId, taskId);
        if (task.getStatus() == VolunteerTaskStatus.CANCELLED) {
            throw new BadRequestException("This task has been cancelled.");
        }
        task.setStatus(VolunteerTaskStatus.COMPLETED);
        task.setCompletedAt(Instant.now());
        if (task.getStartedAt() == null) {
            task.setStartedAt(Instant.now());
        }
        if (request != null && request.completionNotes() != null) {
            task.setCompletionNotes(request.completionNotes());
        }
        VolunteerTask saved = taskRepository.save(task);

        // Notify the assigner (coordinator/member) that the task is done.
        if (task.getAssignedBy() != null) {
            notificationService.notifyUser(task.getAssignedBy().getId(), NotificationType.GENERAL,
                    "Volunteer task completed",
                    task.getVolunteer().getUser().getFullName() + " completed \"" + task.getTitle() + "\".",
                    "/app/manage/events/" + task.getEvent().getId());
        }
        return toTaskResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerScheduleItemResponse> mySchedule(Long userId) {
        List<Long> ids = myVolunteerIds(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return assignmentRepository.findByVolunteerIdIn(ids).stream()
                .filter(a -> a.getStatus() != VolunteerAssignmentStatus.CANCELLED)
                .sorted(Comparator.comparing(VolunteerAssignment::getShiftStart,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(a -> new VolunteerScheduleItemResponse(
                        a.getId(), a.getEvent().getId(), a.getEvent().getTitle(),
                        a.getRole(), a.getLocation(), a.getShiftStart(), a.getShiftEnd(), a.getStatus()))
                .toList();
    }

    @Override
    @Transactional
    public VolunteerAttendanceResponse checkIn(Long userId, Long eventId) {
        Event event = requireEvent(eventId);
        Volunteer volunteer = requireAssignedVolunteer(userId, eventId);
        if (attendanceRepository.existsByVolunteerIdAndEventId(volunteer.getId(), eventId)) {
            throw new ConflictException("You have already checked in for this event.");
        }
        VolunteerAttendance attendance = VolunteerAttendance.builder()
                .volunteer(volunteer)
                .event(event)
                .checkInTime(Instant.now())
                .status(VolunteerAttendanceStatus.PRESENT)
                .build();
        return toAttendanceResponse(attendanceRepository.save(attendance));
    }

    @Override
    @Transactional
    public VolunteerAttendanceResponse checkOut(Long userId, Long eventId) {
        Volunteer volunteer = requireAssignedVolunteer(userId, eventId);
        VolunteerAttendance attendance = attendanceRepository
                .findByVolunteerIdAndEventId(volunteer.getId(), eventId)
                .orElseThrow(() -> new BadRequestException("Check in before checking out."));
        if (attendance.getCheckOutTime() != null) {
            throw new ConflictException("You have already checked out for this event.");
        }
        Instant now = Instant.now();
        attendance.setCheckOutTime(now);
        double hours = 0d;
        if (attendance.getCheckInTime() != null) {
            hours = Math.max(0d,
                    Duration.between(attendance.getCheckInTime(), now).toMinutes() / 60.0);
            hours = Math.round(hours * 100.0) / 100.0;
        }
        attendance.setHoursWorked(hours);
        VolunteerAttendance saved = attendanceRepository.save(attendance);

        // Roll the shift hours into the volunteer's running total.
        volunteer.setTotalHours(Math.round((volunteer.getTotalHours() + hours) * 100.0) / 100.0);
        volunteerRepository.save(volunteer);
        return toAttendanceResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerAttendanceResponse> myAttendance(Long userId) {
        List<Long> ids = myVolunteerIds(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<VolunteerAttendance> rows = new ArrayList<>();
        for (Long id : ids) {
            rows.addAll(attendanceRepository.findByVolunteerId(id));
        }
        return rows.stream()
                .sorted(Comparator.comparing(VolunteerAttendance::getCheckInTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toAttendanceResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VolunteerHoursResponse myHours(Long userId) {
        List<Volunteer> profiles = volunteerRepository.findByUserId(userId);
        if (profiles.isEmpty()) {
            return new VolunteerHoursResponse(0d, 0, 0, 0, 0d, List.of(), List.of());
        }
        List<Long> ids = profiles.stream().map(Volunteer::getId).toList();

        List<VolunteerAttendance> rows = new ArrayList<>();
        for (Long id : ids) {
            rows.addAll(attendanceRepository.findByVolunteerId(id));
        }
        double totalHours = Math.round(rows.stream().mapToDouble(VolunteerAttendance::getHoursWorked).sum() * 100.0) / 100.0;

        int eventsSupported = (int) assignmentRepository.findByVolunteerIdIn(ids).stream()
                .map(a -> a.getEvent().getId()).distinct().count();

        int tasksCompleted = (int) taskRepository.findByVolunteerIdIn(ids).stream()
                .filter(t -> t.getStatus() == VolunteerTaskStatus.COMPLETED).count();

        int attendancePct = attendancePercentage(ids);
        double avg = eventsSupported == 0 ? 0d
                : Math.round((totalHours / eventsSupported) * 100.0) / 100.0;

        // Per-event hours.
        Map<Long, VolunteerHoursResponse.EventHours> byEvent = new LinkedHashMap<>();
        for (VolunteerAttendance row : rows) {
            Event e = row.getEvent();
            byEvent.merge(e.getId(),
                    new VolunteerHoursResponse.EventHours(e.getId(), e.getTitle(), row.getHoursWorked()),
                    (a, b) -> new VolunteerHoursResponse.EventHours(a.eventId(), a.eventTitle(),
                            Math.round((a.hours() + b.hours()) * 100.0) / 100.0));
        }

        // Per-month hours (keyed by ISO year-month), chronological.
        Map<String, Double> monthTotals = new LinkedHashMap<>();
        rows.stream()
                .filter(r -> r.getCheckInTime() != null)
                .sorted(Comparator.comparing(VolunteerAttendance::getCheckInTime))
                .forEach(r -> {
                    String key = YearMonth.from(r.getCheckInTime().atZone(ZONE)).toString();
                    monthTotals.merge(key, r.getHoursWorked(), Double::sum);
                });
        List<VolunteerHoursResponse.MonthHours> byMonth = monthTotals.entrySet().stream()
                .map(en -> new VolunteerHoursResponse.MonthHours(en.getKey(),
                        Math.round(en.getValue() * 100.0) / 100.0))
                .toList();

        return new VolunteerHoursResponse(totalHours, eventsSupported, tasksCompleted,
                attendancePct, avg, List.copyOf(byEvent.values()), byMonth);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerAchievementResponse> myAchievements(Long userId) {
        List<Volunteer> profiles = volunteerRepository.findByUserId(userId);
        List<Long> ids = profiles.stream().map(Volunteer::getId).toList();

        double totalHours = profiles.stream().mapToDouble(Volunteer::getTotalHours).sum();
        long tasksCompleted = ids.isEmpty() ? 0 : taskRepository.findByVolunteerIdIn(ids).stream()
                .filter(t -> t.getStatus() == VolunteerTaskStatus.COMPLETED).count();
        long eventsSupported = ids.isEmpty() ? 0 : assignmentRepository.findByVolunteerIdIn(ids).stream()
                .map(a -> a.getEvent().getId()).distinct().count();
        int attendancePct = ids.isEmpty() ? 0 : attendancePercentage(ids);

        List<VolunteerAchievementResponse> badges = new ArrayList<>();
        badges.add(badge("FIRST_EVENT", "First Event", "Supported your first event", "flag",
                eventsSupported >= 1));
        badges.add(badge("TEN_TASKS", "10 Tasks Completed", "Completed 10 volunteer tasks", "check-circle",
                tasksCompleted >= 10));
        badges.add(badge("TWENTYFIVE_HOURS", "25 Hours Volunteering", "Logged 25 volunteer hours", "clock",
                totalHours >= 25));
        badges.add(badge("EVENT_CHAMPION", "Event Champion", "Supported 5 or more events", "trophy",
                eventsSupported >= 5));
        badges.add(badge("OUTSTANDING", "Outstanding Volunteer", "50+ hours volunteered", "star",
                totalHours >= 50));
        badges.add(badge("RELIABLE", "Reliable Volunteer", "95%+ attendance across shifts", "shield-check",
                attendancePct >= 95));
        badges.add(badge("TEAM_SUPPORTER", "Team Supporter", "Completed 25 volunteer tasks", "users",
                tasksCompleted >= 25));
        return badges;
    }

    @Override
    @Transactional
    public VolunteerScanResponse scan(Long userId, VolunteerScanRequest request) {
        Event event = requireEvent(request.eventId());

        // 1) The scanner must be an assigned volunteer for this event with check-in duty.
        Volunteer volunteer = requireAssignedVolunteer(userId, request.eventId());
        VolunteerAssignment assignment = assignmentRepository
                .findByVolunteerIdAndEventId(volunteer.getId(), request.eventId())
                .orElseThrow(() -> new ForbiddenException("You are not assigned to this event."));
        if (!assignment.isCheckInDuty()) {
            throw new ForbiddenException("You have not been granted check-in duty for this event.");
        }

        // 2) Resolve the ticket. The QR payload carries only the opaque ticket code.
        Registration registration = registrationRepository.findByTicketCode(request.ticketCode().trim())
                .orElseThrow(() -> new BadRequestException("Unrecognised ticket. Please try again."));

        // 3) The ticket must belong to THIS event (no cross-event check-ins).
        if (!registration.getEvent().getId().equals(event.getId())) {
            throw new BadRequestException("This ticket is for a different event.");
        }

        // 4) Registration must be valid.
        RegistrationStatus status = registration.getStatus();
        if (status == RegistrationStatus.CANCELLED) {
            throw new BadRequestException("This registration has been cancelled.");
        }
        if (status == RegistrationStatus.WAITLISTED) {
            throw new BadRequestException("This participant is waitlisted and cannot be checked in.");
        }
        // For a paid event the ticket is only valid once payment has cleared (status CONFIRMED).
        if (event.isPaidEvent() && event.getFee() != null && event.getFee().signum() > 0
                && status != RegistrationStatus.CONFIRMED) {
            throw new BadRequestException("This participant has not completed payment for this event.");
        }

        // 5) Prevent duplicate check-in.
        if (participantAttendanceRepository.existsByRegistrationId(registration.getId())) {
            throw new ConflictException("This participant has already been checked in.");
        }

        User marker = requireUser(userId);
        Instant now = Instant.now();
        Attendance attendance = Attendance.builder()
                .registration(registration)
                .event(event)
                .user(registration.getUser())
                .method(AttendanceMethod.QR)
                .checkInAt(now)
                .markedBy(marker)
                .build();
        participantAttendanceRepository.save(attendance);

        return new VolunteerScanResponse(true,
                registration.getUser().getFullName(),
                event.getId(), event.getTitle(),
                registration.getId(), now, "PRESENT",
                "Participant verified and checked in.");
    }

    // ======================= coordinator / member =======================

    @Override
    @Transactional
    public VolunteerResponse addVolunteer(Long actingUserId, Long clubId, VolunteerAddRequest request) {
        // Volunteers are added by an active member of the club (including its
        // coordinators) or by a platform admin — there is no public self-apply.
        // The person being added must already have a CampusConnect account; we
        // look them up by the email they registered with. An admin operates the
        // roster afterwards (approve / suspend / remove).
        clubAccess.requireAdminOrActiveMember(clubId, actingUserId);
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new ResourceNotFoundException("Club", "id", clubId));
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException(
                        "No CampusConnect account is registered with the email '" + email
                                + "'. Ask them to create an account first, then add them as a volunteer."));

        Volunteer volunteer = volunteerRepository.findByUserIdAndClubId(user.getId(), clubId).orElse(null);
        if (volunteer != null) {
            if (volunteer.getStatus() == VolunteerStatus.ACTIVE) {
                throw new ConflictException(user.getFullName() + " is already a volunteer for this club.");
            }
            // Re-activate a previously removed/inactive profile rather than duplicating it.
            volunteer.setStatus(VolunteerStatus.ACTIVE);
            if (request.skills() != null) {
                volunteer.setSkills(request.skills());
            }
            if (request.availability() != null) {
                volunteer.setAvailability(request.availability());
            }
        } else {
            volunteer = Volunteer.builder()
                    .user(user)
                    .club(club)
                    .status(VolunteerStatus.ACTIVE)
                    .skills(request.skills())
                    .availability(request.availability())
                    .build();
        }
        Volunteer saved = volunteerRepository.save(volunteer);

        // Grant the operational VOLUNTEER role so the user can reach the volunteer
        // workspace. Only a plain STUDENT is elevated — members, coordinators and
        // admins keep their (higher) role.
        if (user.getRole() == Role.STUDENT) {
            user.setRole(Role.VOLUNTEER);
            userRepository.save(user);
        }

        notificationService.notifyUser(user.getId(), NotificationType.GENERAL,
                "You're now a volunteer",
                "You have been added as a volunteer for " + club.getName() + ".",
                "/app/volunteer/dashboard");
        return toVolunteerResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerResponse> clubVolunteers(Long actingUserId, Long clubId) {
        clubAccess.requireAdminOrCoordinator(clubId, actingUserId);
        return volunteerRepository.findByClubId(clubId).stream()
                .map(this::toVolunteerResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VolunteerResponse getVolunteer(Long actingUserId, Long volunteerId) {
        Volunteer volunteer = requireVolunteer(volunteerId);
        clubAccess.requireAdminOrCoordinator(volunteer.getClub().getId(), actingUserId);
        return toVolunteerResponse(volunteer);
    }

    @Override
    @Transactional
    public VolunteerResponse approveVolunteer(Long actingUserId, Long volunteerId) {
        Volunteer volunteer = requireVolunteer(volunteerId);
        clubAccess.requireAdminOrCoordinator(volunteer.getClub().getId(), actingUserId);
        volunteer.setStatus(VolunteerStatus.ACTIVE);
        Volunteer saved = volunteerRepository.save(volunteer);

        // Grant the operational VOLUNTEER role so the user can reach the volunteer
        // workspace. Only a plain STUDENT is elevated — members, coordinators and
        // admins keep their (higher) role so approving them here never strips
        // existing privileges.
        User volunteerUser = volunteer.getUser();
        if (volunteerUser.getRole() == Role.STUDENT) {
            volunteerUser.setRole(Role.VOLUNTEER);
            userRepository.save(volunteerUser);
        }

        notificationService.notifyUser(volunteer.getUser().getId(), NotificationType.GENERAL,
                "Volunteer application approved",
                "You are now an active volunteer for " + volunteer.getClub().getName() + ".",
                "/app/volunteer/dashboard");
        return toVolunteerResponse(saved);
    }

    @Override
    @Transactional
    public VolunteerResponse rejectVolunteer(Long actingUserId, Long volunteerId) {
        Volunteer volunteer = requireVolunteer(volunteerId);
        clubAccess.requireAdminOrCoordinator(volunteer.getClub().getId(), actingUserId);
        volunteer.setStatus(VolunteerStatus.INACTIVE);
        Volunteer saved = volunteerRepository.save(volunteer);

        // If this was the user's last active volunteer profile and their primary
        // role is VOLUNTEER (i.e. they were elevated purely for volunteering),
        // drop them back to STUDENT. Users with a higher role are left untouched.
        User volunteerUser = volunteer.getUser();
        if (volunteerUser.getRole() == Role.VOLUNTEER
                && volunteerRepository.findFirstByUserIdAndStatus(
                        volunteerUser.getId(), VolunteerStatus.ACTIVE).isEmpty()) {
            volunteerUser.setRole(Role.STUDENT);
            userRepository.save(volunteerUser);
        }
        return toVolunteerResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerAssignmentResponse> eventVolunteers(Long actingUserId, Long eventId) {
        Event event = requireEvent(eventId);
        clubAccess.requireAdminOrActiveMember(event.getClub().getId(), actingUserId);
        return assignmentRepository.findByEventId(eventId).stream()
                .map(this::toAssignmentResponse)
                .toList();
    }

    @Override
    @Transactional
    public VolunteerAssignmentResponse assignToEvent(Long actingUserId, Long eventId, VolunteerAssignRequest request) {
        Event event = requireEvent(eventId);
        // Assigning volunteers is a coordinator/admin action.
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);
        Volunteer volunteer = requireVolunteer(request.volunteerId());
        if (volunteer.getStatus() != VolunteerStatus.ACTIVE) {
            throw new BadRequestException("Only active volunteers can be assigned to events.");
        }
        if (assignmentRepository.existsByVolunteerIdAndEventId(volunteer.getId(), eventId)) {
            throw new ConflictException("This volunteer is already assigned to this event.");
        }
        User assigner = requireUser(actingUserId);
        VolunteerAssignment assignment = VolunteerAssignment.builder()
                .volunteer(volunteer)
                .event(event)
                .assignedBy(assigner)
                .role(request.role())
                .location(request.location())
                .shiftStart(request.shiftStart())
                .shiftEnd(request.shiftEnd())
                .checkInDuty(request.checkInDuty())
                .status(VolunteerAssignmentStatus.ASSIGNED)
                .build();
        VolunteerAssignment saved = assignmentRepository.save(assignment);

        notificationService.notifyUser(volunteer.getUser().getId(), NotificationType.GENERAL,
                "New event assignment",
                "You've been assigned as " + request.role() + " for " + event.getTitle() + ".",
                "/app/volunteer/events/" + event.getId());
        return toAssignmentResponse(saved);
    }

    @Override
    @Transactional
    public VolunteerAssignmentResponse cancelAssignment(Long actingUserId, Long assignmentId) {
        VolunteerAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VolunteerAssignment", "id", assignmentId));
        clubAccess.requireAdminOrCoordinator(assignment.getEvent().getClub().getId(), actingUserId);
        assignment.setStatus(VolunteerAssignmentStatus.CANCELLED);
        return toAssignmentResponse(assignmentRepository.save(assignment));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerTaskResponse> eventTasks(Long actingUserId, Long eventId) {
        Event event = requireEvent(eventId);
        clubAccess.requireAdminOrActiveMember(event.getClub().getId(), actingUserId);
        return taskRepository.findByEventId(eventId).stream()
                .map(this::toTaskResponse)
                .toList();
    }

    @Override
    @Transactional
    public VolunteerTaskResponse createTask(Long actingUserId, Long eventId, VolunteerTaskCreateRequest request) {
        Event event = requireEvent(eventId);
        // Members assigned to the event's club may assign tasks (delegated volunteer management).
        clubAccess.requireAdminOrActiveMember(event.getClub().getId(), actingUserId);
        Volunteer volunteer = requireVolunteer(request.volunteerId());
        if (!assignmentRepository.existsByVolunteerIdAndEventId(volunteer.getId(), eventId)) {
            throw new BadRequestException("Assign this volunteer to the event before giving them tasks.");
        }
        User assigner = requireUser(actingUserId);
        VolunteerTask task = VolunteerTask.builder()
                .volunteer(volunteer)
                .event(event)
                .assignedBy(assigner)
                .title(request.title())
                .description(request.description())
                .instructions(request.instructions())
                .location(request.location())
                .priority(request.priority() != null ? request.priority() : VolunteerTaskPriority.MEDIUM)
                .startTime(request.startTime())
                .endTime(request.endTime())
                .status(VolunteerTaskStatus.PENDING)
                .build();
        VolunteerTask saved = taskRepository.save(task);

        notificationService.notifyUser(volunteer.getUser().getId(), NotificationType.GENERAL,
                "New task assigned",
                "New task \"" + request.title() + "\" for " + event.getTitle() + ".",
                "/app/volunteer/tasks/" + saved.getId());
        return toTaskResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VolunteerAttendanceResponse> eventVolunteerAttendance(Long actingUserId, Long eventId) {
        Event event = requireEvent(eventId);
        clubAccess.requireAdminOrActiveMember(event.getClub().getId(), actingUserId);
        return attendanceRepository.findByEventId(eventId).stream()
                .map(this::toAttendanceResponse)
                .toList();
    }

    // ============================== helpers ==============================

    private List<Long> myVolunteerIds(Long userId) {
        return volunteerRepository.findByUserId(userId).stream().map(Volunteer::getId).toList();
    }

    private Volunteer requireOwnPrimaryProfile(Long userId) {
        List<Volunteer> profiles = volunteerRepository.findByUserId(userId);
        if (profiles.isEmpty()) {
            throw new ResourceNotFoundException("Volunteer", "userId", userId);
        }
        // Prefer an ACTIVE profile; otherwise the first.
        return profiles.stream().filter(p -> p.getStatus() == VolunteerStatus.ACTIVE)
                .findFirst().orElse(profiles.get(0));
    }

    private VolunteerAssignment requireOwnAssignment(Long userId, Long assignmentId) {
        VolunteerAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VolunteerAssignment", "id", assignmentId));
        if (!assignment.getVolunteer().getUser().getId().equals(userId)) {
            throw new ForbiddenException("This assignment does not belong to you.");
        }
        return assignment;
    }

    private VolunteerTask requireOwnTask(Long userId, Long taskId) {
        VolunteerTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("VolunteerTask", "id", taskId));
        if (!task.getVolunteer().getUser().getId().equals(userId)) {
            throw new ForbiddenException("This task does not belong to you.");
        }
        return task;
    }

    /** The acting user's ACTIVE volunteer profile that is assigned to the given event. */
    private Volunteer requireAssignedVolunteer(Long userId, Long eventId) {
        List<Volunteer> profiles = volunteerRepository.findByUserId(userId);
        for (Volunteer v : profiles) {
            if (v.getStatus() == VolunteerStatus.ACTIVE
                    && assignmentRepository.existsByVolunteerIdAndEventId(v.getId(), eventId)) {
                return v;
            }
        }
        throw new ForbiddenException("You are not an assigned volunteer for this event.");
    }

    private int attendancePercentage(List<Long> volunteerIds) {
        List<VolunteerAttendance> rows = new ArrayList<>();
        for (Long id : volunteerIds) {
            rows.addAll(attendanceRepository.findByVolunteerId(id));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        long present = rows.stream()
                .filter(r -> r.getStatus() == VolunteerAttendanceStatus.PRESENT
                        || r.getStatus() == VolunteerAttendanceStatus.LATE)
                .count();
        return (int) Math.round(present * 100.0 / rows.size());
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }

    private Volunteer requireVolunteer(Long volunteerId) {
        return volunteerRepository.findById(volunteerId)
                .orElseThrow(() -> new ResourceNotFoundException("Volunteer", "id", volunteerId));
    }

    private Event requireEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", eventId));
    }

    private VolunteerAchievementResponse badge(String code, String name, String desc, String icon, boolean earned) {
        return new VolunteerAchievementResponse(code, name, desc, icon, earned, null);
    }

    // ------------------------------ mappers ------------------------------

    private VolunteerResponse toVolunteerResponse(Volunteer v) {
        User u = v.getUser();
        Club c = v.getClub();
        return new VolunteerResponse(
                v.getId(), u.getId(), u.getFullName(), u.getEmail(), u.getStudentId(),
                u.getDepartment(), u.getPhone(), u.getProfilePhotoUrl(),
                c.getId(), c.getName(), v.getStatus(), v.getSkills(), v.getAvailability(),
                v.getTotalHours(), v.isVolunteerLead(), v.getCreatedAt());
    }

    private VolunteerAssignmentResponse toAssignmentResponse(VolunteerAssignment a) {
        Event e = a.getEvent();
        Club c = e.getClub();
        return new VolunteerAssignmentResponse(
                a.getId(), a.getVolunteer().getId(), a.getVolunteer().getUser().getFullName(),
                e.getId(), e.getTitle(), e.getBannerUrl(), e.getVenue(),
                e.getStartDateTime(), e.getEndDateTime(),
                c != null ? c.getId() : null, c != null ? c.getName() : null,
                a.getRole(), a.getShiftStart(), a.getShiftEnd(), a.getLocation(),
                a.isCheckInDuty(), a.getStatus());
    }

    private VolunteerTaskResponse toTaskResponse(VolunteerTask t) {
        return new VolunteerTaskResponse(
                t.getId(), t.getVolunteer().getId(), t.getVolunteer().getUser().getFullName(),
                t.getEvent().getId(), t.getEvent().getTitle(),
                t.getAssignedBy() != null ? t.getAssignedBy().getFullName() : null,
                t.getTitle(), t.getDescription(), t.getInstructions(), t.getLocation(),
                t.getPriority(), t.getStartTime(), t.getEndTime(), t.getStatus(),
                t.getStartedAt(), t.getCompletedAt(), t.getCompletionNotes(), t.getCreatedAt());
    }

    private VolunteerAttendanceResponse toAttendanceResponse(VolunteerAttendance a) {
        return new VolunteerAttendanceResponse(
                a.getId(), a.getVolunteer().getId(), a.getVolunteer().getUser().getFullName(),
                a.getEvent().getId(), a.getEvent().getTitle(),
                a.getCheckInTime(), a.getCheckOutTime(), a.getHoursWorked(), a.getStatus());
    }
}
