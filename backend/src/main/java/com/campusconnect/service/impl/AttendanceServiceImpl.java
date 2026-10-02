package com.campusconnect.service.impl;

import com.campusconnect.dto.request.CheckInRequest;
import com.campusconnect.dto.request.ManualCheckInRequest;
import com.campusconnect.dto.response.AttendanceResponse;
import com.campusconnect.entity.Attendance;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.Registration;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.AttendanceMethod;
import com.campusconnect.entity.enums.RegistrationStatus;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ConflictException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.AttendanceMapper;
import com.campusconnect.repository.AttendanceRepository;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.RegistrationRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final RegistrationRepository registrationRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final ClubAccess clubAccess;

    @Override
    @Transactional
    public AttendanceResponse checkInByTicket(Long actingUserId, CheckInRequest request) {
        Registration registration = registrationRepository.findByTicketCode(request.ticketCode().trim())
                .orElseThrow(() -> new ResourceNotFoundException("Registration", "ticketCode", request.ticketCode()));
        return checkIn(actingUserId, registration, AttendanceMethod.QR);
    }

    @Override
    @Transactional
    public AttendanceResponse checkInManual(Long actingUserId, ManualCheckInRequest request) {
        Registration registration = registrationRepository.findById(request.registrationId())
                .orElseThrow(() -> new ResourceNotFoundException("Registration", "id", request.registrationId()));
        return checkIn(actingUserId, registration, AttendanceMethod.MANUAL);
    }

    @Override
    @Transactional
    public AttendanceResponse checkOut(Long actingUserId, Long attendanceId) {
        Attendance attendance = attendanceRepository.findById(attendanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance", "id", attendanceId));
        requireEventClubMember(attendance.getEvent(), actingUserId);
        if (attendance.getCheckOutAt() == null) {
            attendance.setCheckOutAt(Instant.now());
            attendance = attendanceRepository.save(attendance);
        }
        return AttendanceMapper.toResponse(attendance);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceResponse> eventAttendance(Long actingUserId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", eventId));
        requireEventClubMember(event, actingUserId);
        return attendanceRepository.findByEventId(eventId).stream()
                .map(AttendanceMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceResponse> myAttendance(Long userId) {
        return attendanceRepository.findByUserId(userId).stream()
                .map(AttendanceMapper::toResponse)
                .toList();
    }

    // ---- helpers ----

    private AttendanceResponse checkIn(Long actingUserId, Registration registration, AttendanceMethod method) {
        Event event = registration.getEvent();
        requireEventClubMember(event, actingUserId);

        RegistrationStatus status = registration.getStatus();
        if (status == RegistrationStatus.CANCELLED) {
            throw new BadRequestException("This registration has been cancelled.");
        }
        if (status == RegistrationStatus.WAITLISTED) {
            throw new BadRequestException("This participant is waitlisted and cannot be checked in.");
        }
        // For a paid event, a seat is only valid once payment has cleared (status CONFIRMED). This
        // is defence-in-depth: the ticket UI is already gated on payment, but block check-in too.
        if (event.isPaidEvent() && event.getFee() != null && event.getFee().signum() > 0
                && status != RegistrationStatus.CONFIRMED) {
            throw new BadRequestException("This participant has not completed payment for this event.");
        }
        if (attendanceRepository.existsByRegistrationId(registration.getId())) {
            throw new ConflictException("This participant has already been checked in.");
        }

        User marker = userRepository.findById(actingUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", actingUserId));

        Attendance attendance = Attendance.builder()
                .registration(registration)
                .event(event)
                .user(registration.getUser())
                .method(method)
                .checkInAt(Instant.now())
                .markedBy(marker)
                .build();
        return AttendanceMapper.toResponse(attendanceRepository.save(attendance));
    }

    private void requireEventClubMember(Event event, Long actingUserId) {
        if (event == null || event.getClub() == null) {
            throw new BadRequestException("This event is not associated with a club.");
        }
        // A platform admin, or any active member of the owning club, may manage attendance.
        clubAccess.requireAdminOrActiveMember(event.getClub().getId(), actingUserId);
    }
}
