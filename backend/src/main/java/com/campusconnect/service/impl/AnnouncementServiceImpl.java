package com.campusconnect.service.impl;

import com.campusconnect.dto.request.AnnouncementRequest;
import com.campusconnect.dto.request.AnnouncementUpdateRequest;
import com.campusconnect.dto.response.AnnouncementResponse;
import com.campusconnect.entity.Announcement;
import com.campusconnect.entity.Club;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.AnnouncementScope;
import com.campusconnect.entity.enums.MembershipStatus;
import com.campusconnect.entity.enums.NotificationType;
import com.campusconnect.entity.enums.RegistrationStatus;
import com.campusconnect.entity.enums.Role;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ForbiddenException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.AnnouncementMapper;
import com.campusconnect.repository.AnnouncementRepository;
import com.campusconnect.repository.ClubMemberRepository;
import com.campusconnect.repository.ClubRepository;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.RegistrationRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.service.AnnouncementService;
import com.campusconnect.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnnouncementServiceImpl implements AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final ClubRepository clubRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final ClubMemberRepository clubMemberRepository;
    private final RegistrationRepository registrationRepository;
    private final ClubAccess clubAccess;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public AnnouncementResponse create(Long actingUserId, AnnouncementRequest request) {
        User author = userRepository.findById(actingUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", actingUserId));

        Announcement announcement = Announcement.builder()
                .scope(request.scope())
                .title(request.title().trim())
                .content(request.content())
                .pinned(request.pinned())
                .author(author)
                .build();

        switch (request.scope()) {
            case CLUB -> {
                if (request.clubId() == null) {
                    throw new BadRequestException("clubId is required for a CLUB announcement.");
                }
                Club club = clubRepository.findById(request.clubId())
                        .orElseThrow(() -> new ResourceNotFoundException("Club", "id", request.clubId()));
                clubAccess.requireCoordinator(club.getId(), actingUserId);
                announcement.setClub(club);
                announcement = announcementRepository.save(announcement);
                fanOutToClubMembers(club, announcement, actingUserId);
            }
            case EVENT -> {
                if (request.eventId() == null) {
                    throw new BadRequestException("eventId is required for an EVENT announcement.");
                }
                Event event = eventRepository.findById(request.eventId())
                        .orElseThrow(() -> new ResourceNotFoundException("Event", "id", request.eventId()));
                clubAccess.requireCoordinator(event.getClub().getId(), actingUserId);
                announcement.setEvent(event);
                announcement.setClub(event.getClub());
                announcement = announcementRepository.save(announcement);
                fanOutToEventRegistrants(event, announcement, actingUserId);
            }
            case GENERAL -> {
                if (author.getRole() != Role.CLUB_COORDINATOR && author.getRole() != Role.ADMIN) {
                    throw new ForbiddenException("Only club coordinators or admins can post campus-wide announcements.");
                }
                announcement = announcementRepository.save(announcement);
                // General announcements are surfaced via the public feed rather than per-user push.
            }
        }
        return AnnouncementMapper.toResponse(announcement);
    }

    @Override
    @Transactional
    public AnnouncementResponse update(Long actingUserId, Long announcementId, AnnouncementUpdateRequest request) {
        Announcement announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement", "id", announcementId));

        boolean isAuthor = announcement.getAuthor() != null
                && announcement.getAuthor().getId().equals(actingUserId);
        if (!isAuthor && !canManage(announcement, actingUserId)) {
            throw new ForbiddenException("You are not allowed to edit this announcement.");
        }
        announcement.setTitle(request.title().trim());
        announcement.setContent(request.content());
        announcement.setPinned(request.pinned());
        return AnnouncementMapper.toResponse(announcementRepository.save(announcement));
    }

    @Override
    @Transactional
    public void delete(Long actingUserId, Long announcementId) {
        Announcement announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement", "id", announcementId));

        boolean isAuthor = announcement.getAuthor() != null
                && announcement.getAuthor().getId().equals(actingUserId);
        if (!isAuthor && !canManage(announcement, actingUserId)) {
            throw new ForbiddenException("You are not allowed to delete this announcement.");
        }
        announcementRepository.delete(announcement);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listByClub(Long clubId) {
        if (!clubRepository.existsById(clubId)) {
            throw new ResourceNotFoundException("Club", "id", clubId);
        }
        return announcementRepository.findByClubIdOrderByPinnedDescCreatedAtDesc(clubId).stream()
                .map(AnnouncementMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listByEvent(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event", "id", eventId);
        }
        return announcementRepository.findByEventIdOrderByPinnedDescCreatedAtDesc(eventId).stream()
                .map(AnnouncementMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listGeneral() {
        return announcementRepository.findByScopeOrderByPinnedDescCreatedAtDesc(AnnouncementScope.GENERAL).stream()
                .map(AnnouncementMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> feed() {
        return announcementRepository.findAllByOrderByPinnedDescCreatedAtDesc().stream()
                .map(AnnouncementMapper::toResponse)
                .toList();
    }

    // ---- helpers ----

    private boolean canManage(Announcement announcement, Long userId) {
        // Platform admins may moderate (edit/delete) any announcement, matching the
        // admin-inclusive moderation model used for discussion comments and media.
        if (announcement.getScope() == AnnouncementScope.CLUB && announcement.getClub() != null) {
            return clubAccess.isAdminOrCoordinator(announcement.getClub().getId(), userId);
        }
        if (announcement.getScope() == AnnouncementScope.EVENT && announcement.getEvent() != null
                && announcement.getEvent().getClub() != null) {
            return clubAccess.isAdminOrCoordinator(announcement.getEvent().getClub().getId(), userId);
        }
        return clubAccess.isPlatformAdmin(userId);
    }

    private void fanOutToClubMembers(Club club, Announcement announcement, Long actingUserId) {
        String link = "/clubs/" + club.getId();
        for (var member : clubMemberRepository.findByClubId(club.getId())) {
            if (member.getStatus() != MembershipStatus.ACTIVE || member.getUser() == null) {
                continue;
            }
            Long recipientId = member.getUser().getId();
            if (recipientId.equals(actingUserId)) {
                continue;
            }
            notificationService.notifyUser(recipientId, NotificationType.ANNOUNCEMENT,
                    announcement.getTitle(),
                    "New announcement in " + club.getName(), link);
        }
    }

    private void fanOutToEventRegistrants(Event event, Announcement announcement, Long actingUserId) {
        String link = "/events/" + event.getId();
        for (var registration : registrationRepository.findByEventId(event.getId())) {
            if (registration.getStatus() == RegistrationStatus.CANCELLED || registration.getUser() == null) {
                continue;
            }
            Long recipientId = registration.getUser().getId();
            if (recipientId.equals(actingUserId)) {
                continue;
            }
            notificationService.notifyUser(recipientId, NotificationType.ANNOUNCEMENT,
                    announcement.getTitle(),
                    "New update for " + event.getTitle(), link);
        }
    }
}
