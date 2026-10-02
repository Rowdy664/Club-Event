package com.campusconnect.service.impl;

import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.response.AdminUserResponse;
import com.campusconnect.dto.response.ClubResponse;
import com.campusconnect.dto.response.EventResponse;
import com.campusconnect.dto.response.EventSummaryResponse;
import com.campusconnect.dto.response.PaymentResponse;
import com.campusconnect.dto.response.PlatformStatsResponse;
import com.campusconnect.entity.Club;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.Payment;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.AuditAction;
import com.campusconnect.entity.enums.EventStatus;
import com.campusconnect.entity.enums.PaymentStatus;
import com.campusconnect.entity.enums.Role;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ConflictException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.ClubMapper;
import com.campusconnect.mapper.EventMapper;
import com.campusconnect.mapper.PaymentMapper;
import com.campusconnect.mapper.UserMapper;
import com.campusconnect.repository.ClubMemberRepository;
import com.campusconnect.repository.ClubRepository;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.PaymentRepository;
import com.campusconnect.repository.RegistrationRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.service.AdminService;
import com.campusconnect.service.AuditService;
import com.campusconnect.service.CertificateService;
import com.campusconnect.service.EntityPurgeService;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;
    private final ClubRepository clubRepository;
    private final ClubMemberRepository clubMemberRepository;
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final PaymentRepository paymentRepository;
    private final CertificateService certificateService;
    private final EntityPurgeService entityPurgeService;
    private final AuditService auditService;

    // ----------------------------------------------------------------- stats

    @Override
    @Transactional(readOnly = true)
    public PlatformStatsResponse platformStats() {
        return new PlatformStatsResponse(
                userRepository.count(),
                userRepository.countByRole(Role.STUDENT),
                userRepository.countByRole(Role.CLUB_MEMBER),
                userRepository.countByRole(Role.CLUB_COORDINATOR),
                userRepository.countByRole(Role.ADMIN),
                clubRepository.count(),
                clubRepository.countByActive(true),
                eventRepository.count(),
                eventRepository.countByStatus(EventStatus.PUBLISHED),
                registrationRepository.count(),
                paymentRepository.countByStatus(PaymentStatus.SUCCESS),
                paymentRepository.sumAmountByStatus(PaymentStatus.SUCCESS)
        );
    }

    // ----------------------------------------------------------------- users

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> listUsers(String q, Role role, Pageable pageable) {
        String query = (q == null || q.isBlank()) ? null : q.trim();
        return PageResponse.from(userRepository.search(query, role, pageable), UserMapper::toAdminResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserResponse getUser(Long id) {
        return UserMapper.toAdminResponse(getUserOrThrow(id));
    }

    @Override
    @Transactional
    public AdminUserResponse updateRole(Long actingAdminId, Long userId, Role role) {
        User user = getUserOrThrow(userId);
        if (user.getId().equals(actingAdminId) && role != Role.ADMIN) {
            throw new BadRequestException("You cannot remove your own admin role.");
        }
        if (user.getRole() == Role.ADMIN && role != Role.ADMIN) {
            guardLastAdmin();
        }
        Role previousRole = user.getRole();
        user.setRole(role);
        AdminUserResponse saved = UserMapper.toAdminResponse(userRepository.save(user));
        auditService.record(actingAdminId, actorName(actingAdminId), AuditAction.USER_ROLE_CHANGED,
                "USER", user.getId(), user.getFullName(), previousRole + " → " + role);
        return saved;
    }

    @Override
    @Transactional
    public AdminUserResponse updateStatus(Long actingAdminId, Long userId, boolean enabled) {
        User user = getUserOrThrow(userId);
        if (!enabled) {
            if (user.getId().equals(actingAdminId)) {
                throw new BadRequestException("You cannot disable your own account.");
            }
            if (user.getRole() == Role.ADMIN) {
                guardLastAdmin();
            }
        }
        user.setEnabled(enabled);
        AdminUserResponse saved = UserMapper.toAdminResponse(userRepository.save(user));
        auditService.record(actingAdminId, actorName(actingAdminId),
                enabled ? AuditAction.USER_ENABLED : AuditAction.USER_DISABLED,
                "USER", user.getId(), user.getFullName(), null);
        return saved;
    }

    @Override
    @Transactional
    public void deleteUser(Long actingAdminId, Long userId) {
        User user = getUserOrThrow(userId);
        if (user.getId().equals(actingAdminId)) {
            throw new BadRequestException("You cannot delete your own account.");
        }
        if (user.getRole() == Role.ADMIN) {
            guardLastAdmin();
        }
        String label = user.getFullName();
        Long deletedId = user.getId();
        // Cascade-purge the user and every dependent row (registrations, memberships,
        // certificates, payments, teams they lead, volunteer profile, etc.) in
        // dependency order. The schema has no ON DELETE CASCADE, so a plain delete
        // would trip an FK violation the moment any child row exists (previously
        // surfaced as a confusing 409). Content that belongs to the platform or other
        // users (their events/clubs, gallery media, other people's attendance) is
        // preserved by nulling the author/creator pointer inside the purge.
        try {
            entityPurgeService.purgeUser(userId);
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            throw new ConflictException(
                    "This user could not be deleted because related data is still referenced elsewhere.");
        }
        auditService.record(actingAdminId, actorName(actingAdminId), AuditAction.USER_DELETED,
                "USER", deletedId, label, null);
    }

    // ----------------------------------------------------------------- clubs

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ClubResponse> listClubs(Pageable pageable) {
        return PageResponse.from(clubRepository.findAll(pageable), this::toClubResponse);
    }

    @Override
    @Transactional
    public ClubResponse setClubActive(Long actingAdminId, Long clubId, boolean active) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new ResourceNotFoundException("Club", "id", clubId));
        club.setActive(active);
        ClubResponse saved = toClubResponse(clubRepository.save(club));
        auditService.record(actingAdminId, actorName(actingAdminId),
                active ? AuditAction.CLUB_ACTIVATED : AuditAction.CLUB_DEACTIVATED,
                "CLUB", club.getId(), club.getName(), null);
        return saved;
    }

    @Override
    @Transactional
    public void deleteClub(Long actingAdminId, Long clubId) {
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> new ResourceNotFoundException("Club", "id", clubId));
        String label = club.getName();
        Long deletedId = club.getId();
        // Cascade-purge the club and every event/member/follow beneath it. The schema
        // has no ON DELETE CASCADE, so children must be removed first or the FK
        // constraint blocks the delete (previously surfaced as a confusing 409).
        try {
            entityPurgeService.purgeClub(clubId);
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            throw new ConflictException(
                    "This club could not be deleted because related data is still referenced elsewhere.");
        }
        auditService.record(actingAdminId, actorName(actingAdminId), AuditAction.CLUB_DELETED,
                "CLUB", deletedId, label, null);
    }

    // ---------------------------------------------------------------- events

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EventSummaryResponse> listEvents(Pageable pageable) {
        return PageResponse.from(eventRepository.findAll(pageable),
                e -> EventMapper.toSummary(e, registrationRepository.countByEventId(e.getId())));
    }

    @Override
    @Transactional
    public EventResponse updateEventStatus(Long actingAdminId, Long eventId, EventStatus status) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", eventId));
        EventStatus previous = event.getStatus();
        event.setStatus(status);
        Event saved = eventRepository.save(event);
        if (status == EventStatus.COMPLETED && previous != EventStatus.COMPLETED) {
            certificateService.autoIssueForCompletedEvent(saved.getId());
        }
        auditService.record(actingAdminId, actorName(actingAdminId), AuditAction.EVENT_STATUS_CHANGED,
                "EVENT", saved.getId(), saved.getTitle(), previous + " → " + status);
        return EventMapper.toResponse(saved, registrationRepository.countByEventId(saved.getId()));
    }

    @Override
    @Transactional
    public void deleteEvent(Long actingAdminId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", eventId));
        String label = event.getTitle();
        Long deletedId = event.getId();
        // Cascade-purge registrations, payments, certificates, competitions, etc.
        // before removing the event (no ON DELETE CASCADE in the schema).
        try {
            entityPurgeService.purgeEvent(eventId);
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            throw new ConflictException(
                    "This event could not be deleted because related data is still referenced elsewhere.");
        }
        auditService.record(actingAdminId, actorName(actingAdminId), AuditAction.EVENT_DELETED,
                "EVENT", deletedId, label, null);
    }

    // -------------------------------------------------------------- payments

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listPayments(PaymentStatus status, Pageable pageable) {
        Page<Payment> page = (status == null)
                ? paymentRepository.findAll(pageable)
                : paymentRepository.findByStatus(status, pageable);
        return PageResponse.from(page, PaymentMapper::toResponse);
    }

    // --------------------------------------------------------------- helpers

    private User getUserOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    /** Best-effort lookup of the acting admin's display name for the audit snapshot. */
    private String actorName(Long actorId) {
        if (actorId == null) {
            return null;
        }
        return userRepository.findById(actorId).map(User::getFullName).orElse(null);
    }

    /** Refuse an operation that would leave the platform with zero admins. */
    private void guardLastAdmin() {
        if (userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new ConflictException("At least one admin must remain on the platform.");
        }
    }

    private ClubResponse toClubResponse(Club club) {
        return ClubMapper.toResponse(
                club,
                clubMemberRepository.countByClubId(club.getId()),
                eventRepository.countByClubId(club.getId()));
    }
}
