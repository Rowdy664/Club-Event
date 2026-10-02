package com.campusconnect.service.impl;

import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.RegistrationRequest;
import com.campusconnect.dto.response.RegistrationResponse;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.Registration;
import com.campusconnect.entity.Team;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.EventStatus;
import com.campusconnect.entity.enums.NotificationType;
import com.campusconnect.entity.enums.RegistrationStatus;
import com.campusconnect.entity.enums.RegistrationType;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ConflictException;
import com.campusconnect.exception.ForbiddenException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.RegistrationMapper;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.RegistrationRepository;
import com.campusconnect.repository.TeamMemberRepository;
import com.campusconnect.repository.TeamRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.service.EmailService;
import com.campusconnect.service.NotificationService;
import com.campusconnect.service.QrCodeService;
import com.campusconnect.service.RegistrationService;
import com.campusconnect.service.whatsapp.WhatsAppResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RegistrationServiceImpl implements RegistrationService {

    private static final Set<EventStatus> REGISTRABLE =
            Set.of(EventStatus.PUBLISHED, EventStatus.UPCOMING, EventStatus.ONGOING);
    /** Statuses that occupy a seat against the event capacity (awaiting payment still holds the seat). */
    private static final List<RegistrationStatus> SEAT_HOLDING =
            List.of(RegistrationStatus.REGISTERED, RegistrationStatus.CONFIRMED);
    private static final int QR_SIZE = 300;

    /** Ticket-verification OTP policy. */
    private static final int TICKET_OTP_TTL_MINUTES = 10;
    private static final int MAX_TICKET_OTP_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RegistrationRepository registrationRepository;
    private final EventRepository eventRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final ClubAccess clubAccess;
    private final QrCodeService qrCodeService;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final WhatsAppResolver whatsAppResolver;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public RegistrationResponse register(Long userId, RegistrationRequest request) {
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", request.eventId()));

        if (!REGISTRABLE.contains(event.getStatus())) {
            throw new BadRequestException("Registration is not open for this event.");
        }
        if (event.getRegistrationDeadline() != null
                && LocalDateTime.now().isAfter(event.getRegistrationDeadline())) {
            throw new BadRequestException("The registration deadline for this event has passed.");
        }

        User user = getUser(userId);
        Team team = resolveTeam(event, request.teamId(), userId);

        // Re-activate a previously cancelled registration instead of hitting the unique constraint.
        Registration existing = registrationRepository.findByEventIdAndUserId(event.getId(), userId).orElse(null);
        if (existing != null && existing.getStatus() != RegistrationStatus.CANCELLED) {
            throw new ConflictException("You are already registered for this event.");
        }

        RegistrationStatus status = determineStatus(event);
        RegistrationType type = event.isTeamEvent() ? RegistrationType.TEAM : RegistrationType.INDIVIDUAL;

        Registration registration = existing != null ? existing : new Registration();
        registration.setEvent(event);
        registration.setUser(user);
        registration.setTeam(team);
        registration.setType(type);
        registration.setStatus(status);
        if (registration.getTicketCode() == null) {
            registration.setTicketCode(generateTicketCode());
        }
        return RegistrationMapper.toResponse(registrationRepository.save(registration));
    }

    @Override
    @Transactional
    public void cancel(Long userId, Long registrationId) {
        Registration registration = getRegistration(registrationId);
        boolean isOwner = registration.getUser() != null && registration.getUser().getId().equals(userId);
        Event event = registration.getEvent();
        if (!isOwner) {
            // A platform admin, or a coordinator of the owning club, may cancel on a participant's behalf.
            if (event == null || event.getClub() == null
                    || !clubAccess.isAdminOrCoordinator(event.getClub().getId(), userId)) {
                throw new ForbiddenException(
                        "You can only cancel your own registration unless you administer or coordinate this event.");
            }
        }
        if (registration.getStatus() == RegistrationStatus.CANCELLED) {
            throw new BadRequestException("This registration is already cancelled.");
        }
        RegistrationStatus previous = registration.getStatus();
        registration.setStatus(RegistrationStatus.CANCELLED);
        registrationRepository.save(registration);

        // Releasing a seat that was actually occupied may open a spot for the next
        // person on the waitlist. A cancelled WAITLISTED entry frees nothing.
        if (event != null && SEAT_HOLDING.contains(previous)) {
            promoteFromWaitlist(event);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RegistrationResponse getMyRegistrationForEvent(Long userId, Long eventId) {
        Registration registration = registrationRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Registration", "event", eventId));
        return RegistrationMapper.toResponse(registration);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RegistrationResponse> myRegistrations(Long userId, Pageable pageable) {
        Page<Registration> page = registrationRepository.findByUserId(userId, pageable);
        return PageResponse.from(page, RegistrationMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RegistrationResponse> eventRegistrations(Long actingUserId, Long eventId, Pageable pageable) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", eventId));
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);
        Page<Registration> page = registrationRepository.findByEventId(eventId, pageable);
        return PageResponse.from(page, RegistrationMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] ticketQr(Long userId, Long registrationId) {
        Registration registration = getRegistration(registrationId);
        boolean isOwner = registration.getUser().getId().equals(userId);
        if (!isOwner && !clubAccess.isAdminOrCoordinator(registration.getEvent().getClub().getId(), userId)) {
            throw new ForbiddenException("You are not allowed to view this ticket.");
        }
        return qrCodeService.generatePng(registration.getTicketCode(), QR_SIZE);
    }

    @Override
    @Transactional
    public void requestTicketVerification(Long userId, Long registrationId) {
        Registration registration = getRegistration(registrationId);
        requireOwner(registration, userId);
        if (registration.isTicketVerified()) {
            throw new BadRequestException("This ticket is already verified.");
        }
        if (!isTicketReady(registration)) {
            throw new BadRequestException(
                    "Your ticket isn't ready to verify yet. For a paid event, complete payment first.");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        registration.setTicketOtpHash(passwordEncoder.encode(code));
        registration.setTicketOtpExpiry(Instant.now().plus(TICKET_OTP_TTL_MINUTES, ChronoUnit.MINUTES));
        registration.setTicketOtpAttempts(0);
        registrationRepository.save(registration);

        sendTicketOtp(registration, code);
    }

    @Override
    @Transactional
    public RegistrationResponse confirmTicketVerification(Long userId, Long registrationId, String code) {
        Registration registration = getRegistration(registrationId);
        requireOwner(registration, userId);

        // Idempotent: verifying an already-verified ticket just echoes success.
        if (registration.isTicketVerified()) {
            return RegistrationMapper.toResponse(registration);
        }
        if (registration.getTicketOtpHash() == null
                || registration.getTicketOtpExpiry() == null
                || registration.getTicketOtpExpiry().isBefore(Instant.now())) {
            clearTicketOtp(registration);
            registrationRepository.save(registration);
            throw new BadRequestException("Your verification code has expired. Please request a new one.");
        }
        if (!passwordEncoder.matches(code, registration.getTicketOtpHash())) {
            registration.setTicketOtpAttempts(registration.getTicketOtpAttempts() + 1);
            if (registration.getTicketOtpAttempts() >= MAX_TICKET_OTP_ATTEMPTS) {
                clearTicketOtp(registration);
                registrationRepository.save(registration);
                throw new BadRequestException("Too many incorrect codes. Please request a new one.");
            }
            registrationRepository.save(registration);
            throw new BadRequestException("That verification code is incorrect.");
        }

        // Success — mark verified and burn the code so it can't be replayed.
        registration.setTicketVerified(true);
        registration.setTicketVerifiedAt(Instant.now());
        clearTicketOtp(registration);
        return RegistrationMapper.toResponse(registrationRepository.save(registration));
    }

    @Override
    @Transactional
    public void promoteWaitlist(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", eventId));
        promoteFromWaitlist(event);
    }

    // ---- helpers ----

    private Team resolveTeam(Event event, Long teamId, Long userId) {
        if (event.isTeamEvent()) {
            if (teamId == null) {
                throw new BadRequestException("This is a team event. Please register with a team.");
            }
            Team team = teamRepository.findById(teamId)
                    .orElseThrow(() -> new ResourceNotFoundException("Team", "id", teamId));
            if (team.getEvent() == null || !team.getEvent().getId().equals(event.getId())) {
                throw new BadRequestException("The selected team does not belong to this event.");
            }
            if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
                throw new ForbiddenException("You must be a member of the team to register with it.");
            }
            return team;
        }
        if (teamId != null) {
            throw new BadRequestException("This is an individual event and does not accept team registrations.");
        }
        return null;
    }

    private RegistrationStatus determineStatus(Event event) {
        if (isFull(event)) {
            return RegistrationStatus.WAITLISTED;
        }
        // Paid events stay REGISTERED (pending payment); free events are confirmed immediately.
        return admittedStatus(event);
    }

    /** True when every seat against a finite capacity is currently held. Unlimited events are never full. */
    private boolean isFull(Event event) {
        Integer capacity = event.getCapacity();
        if (capacity == null || capacity <= 0) {
            return false;
        }
        long occupied = registrationRepository.countByEventIdAndStatusIn(event.getId(), SEAT_HOLDING);
        return occupied >= capacity;
    }

    /** The status an admitted registrant should hold: awaiting payment for paid events, confirmed for free ones. */
    private RegistrationStatus admittedStatus(Event event) {
        return event.isPaidEvent() ? RegistrationStatus.REGISTERED : RegistrationStatus.CONFIRMED;
    }

    /**
     * Fill any free seats on an event with the longest-waiting people on the waitlist
     * (first in, first off), notifying each. Best-effort and idempotent: an empty waitlist
     * or an already-full event no-ops. If the event's capacity has been removed entirely
     * (unlimited), every waitlisted attendee is promoted.
     */
    private void promoteFromWaitlist(Event event) {
        List<Registration> waitlisted = registrationRepository
                .findByEventIdAndStatusOrderByCreatedAtAsc(event.getId(), RegistrationStatus.WAITLISTED);
        if (waitlisted.isEmpty()) {
            return;
        }

        Integer capacity = event.getCapacity();
        boolean unlimited = capacity == null || capacity <= 0;
        long occupied = unlimited ? 0L
                : registrationRepository.countByEventIdAndStatusIn(event.getId(), SEAT_HOLDING);
        RegistrationStatus promotedStatus = admittedStatus(event);
        boolean paid = event.isPaidEvent();

        for (Registration next : waitlisted) {
            if (!unlimited && occupied >= capacity) {
                break;
            }
            next.setStatus(promotedStatus);
            registrationRepository.save(next);
            occupied++;

            if (next.getUser() != null) {
                String detail = paid
                        ? " Complete your payment to lock in your seat."
                        : " Your seat is now confirmed.";
                notificationService.notifyUser(
                        next.getUser().getId(),
                        NotificationType.REGISTRATION_CONFIRMATION,
                        "You're off the waitlist",
                        "A spot opened up for \"" + event.getTitle()
                                + "\" and you've been moved off the waitlist." + detail,
                        "/events/" + event.getId());
            }
        }
    }

    private String generateTicketCode() {
        for (int i = 0; i < 5; i++) {
            String code = "TKT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
            if (registrationRepository.findByTicketCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to generate a unique ticket code.");
    }

    private Registration getRegistration(Long registrationId) {
        return registrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException("Registration", "id", registrationId));
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }

    private void requireOwner(Registration registration, Long userId) {
        if (registration.getUser() == null || !registration.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You can only verify your own ticket.");
        }
    }

    private void clearTicketOtp(Registration registration) {
        registration.setTicketOtpHash(null);
        registration.setTicketOtpExpiry(null);
        registration.setTicketOtpAttempts(0);
    }

    /**
     * A ticket is verifiable once it is issued: the seat is active and — for a paid event — payment
     * has cleared (status CONFIRMED). Mirrors the {@code ticketReady} rule in {@link RegistrationMapper}.
     */
    private boolean isTicketReady(Registration registration) {
        Event event = registration.getEvent();
        boolean paidEvent = event != null && event.isPaidEvent()
                && event.getFee() != null && event.getFee().signum() > 0;
        RegistrationStatus status = registration.getStatus();
        boolean active = status == RegistrationStatus.REGISTERED || status == RegistrationStatus.CONFIRMED;
        return active && (!paidEvent || status == RegistrationStatus.CONFIRMED);
    }

    /** Deliver a ticket-verification code by email and (best-effort) WhatsApp. Never throws. */
    private void sendTicketOtp(Registration registration, String code) {
        User user = registration.getUser();
        Event event = registration.getEvent();
        String eventTitle = event != null ? event.getTitle() : "your event";
        emailService.send(user.getEmail(), "Verify your ticket for " + eventTitle,
                ("Hi %s,\n\nUse this code to verify your ticket for \"%s\":\n\n    %s\n\n"
                        + "It expires in %d minutes. If you didn't request this, you can ignore it.")
                        .formatted(user.getFullName(), eventTitle, code, TICKET_OTP_TTL_MINUTES));
        whatsAppResolver.resolve().sendText(user.getPhone(),
                "Your CampusConnect ticket code for \"" + eventTitle + "\" is " + code
                        + ". It expires in " + TICKET_OTP_TTL_MINUTES + " minutes.");
    }
}
