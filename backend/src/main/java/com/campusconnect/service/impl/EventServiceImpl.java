package com.campusconnect.service.impl;

import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.EventRequest;
import com.campusconnect.dto.request.EventScheduleRequest;
import com.campusconnect.dto.request.EventSearchCriteria;
import com.campusconnect.dto.response.EventResponse;
import com.campusconnect.dto.response.EventScheduleResponse;
import com.campusconnect.dto.response.EventSummaryResponse;
import com.campusconnect.entity.Club;
import com.campusconnect.entity.ClubFollow;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.EventSchedule;
import com.campusconnect.entity.Registration;
import com.campusconnect.entity.SavedEvent;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.EventStatus;
import com.campusconnect.entity.enums.NotificationType;
import com.campusconnect.entity.enums.RegistrationStatus;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.EventMapper;
import com.campusconnect.repository.ClubFollowRepository;
import com.campusconnect.repository.ClubRepository;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.EventScheduleRepository;
import com.campusconnect.repository.RegistrationRepository;
import com.campusconnect.repository.SavedEventRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.repository.spec.EventSpecifications;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.service.CertificateService;
import com.campusconnect.service.EventService;
import com.campusconnect.service.NotificationService;
import com.campusconnect.service.RegistrationService;
import com.campusconnect.service.calendar.CalendarExport;
import com.campusconnect.service.calendar.ICalendar;
import com.campusconnect.service.whatsapp.WhatsAppResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {

    private final EventRepository eventRepository;
    private final ClubRepository clubRepository;
    private final EventScheduleRepository eventScheduleRepository;
    private final RegistrationRepository registrationRepository;
    private final UserRepository userRepository;
    private final SavedEventRepository savedEventRepository;
    private final ClubFollowRepository clubFollowRepository;
    private final ClubAccess clubAccess;
    private final CertificateService certificateService;
    private final RegistrationService registrationService;
    private final NotificationService notificationService;
    private final WhatsAppResolver whatsAppResolver;
    private final com.campusconnect.service.EntityPurgeService entityPurgeService;

    @Override
    @Transactional
    public EventResponse create(Long userId, EventRequest request) {
        Club club = clubRepository.findById(request.clubId())
                .orElseThrow(() -> new ResourceNotFoundException("Club", "id", request.clubId()));
        clubAccess.requireActiveMember(club.getId(), userId);
        validateDates(request);

        Event event = Event.builder()
                .title(request.title().trim())
                .description(request.description())
                .category(request.category())
                .bannerUrl(request.bannerUrl())
                .mode(request.mode())
                .venue(request.venue())
                .onlineUrl(request.onlineUrl())
                .startDateTime(request.startDateTime())
                .endDateTime(request.endDateTime())
                .registrationDeadline(request.registrationDeadline())
                .capacity(request.capacity())
                .rules(request.rules())
                .instructions(request.instructions())
                .status(EventStatus.DRAFT)
                .featured(request.featured())
                .club(club)
                .createdBy(getUser(userId))
                .build();
        applyPricingAndTeam(event, request);

        event = eventRepository.save(event);
        return EventMapper.toResponse(event, 0);
    }

    @Override
    @Transactional
    public EventResponse update(Long userId, Long eventId, EventRequest request) {
        Event event = getEvent(eventId);
        clubAccess.requireActiveMember(event.getClub().getId(), userId);
        validateDates(request);

        Integer previousCapacity = event.getCapacity();

        event.setTitle(request.title().trim());
        event.setDescription(request.description());
        event.setCategory(request.category());
        event.setBannerUrl(request.bannerUrl());
        event.setMode(request.mode());
        event.setVenue(request.venue());
        event.setOnlineUrl(request.onlineUrl());
        event.setStartDateTime(request.startDateTime());
        event.setEndDateTime(request.endDateTime());
        event.setRegistrationDeadline(request.registrationDeadline());
        event.setCapacity(request.capacity());
        event.setRules(request.rules());
        event.setInstructions(request.instructions());
        event.setFeatured(request.featured());
        applyPricingAndTeam(event, request);

        event = eventRepository.save(event);

        // Raising (or lifting) the capacity opens seats — pull the longest-waiting attendees in.
        // Lowering it never bumps anyone already admitted.
        if (effectiveCapacity(request.capacity()) > effectiveCapacity(previousCapacity)) {
            registrationService.promoteWaitlist(eventId);
        }

        // Tell everyone who's registered that the event details changed (in-app + email + WhatsApp).
        // Draft events have no registrants, so skip the fan-out for them.
        if (event.getStatus() != EventStatus.DRAFT) {
            notifyRegistrantsOfChange(event, NotificationType.SCHEDULE_UPDATE,
                    "Event updated: " + event.getTitle(),
                    "Details for \"" + event.getTitle()
                            + "\" have changed. Open the event to see the latest information.");
        }
        return EventMapper.toResponse(event, activeRegistrations(eventId));
    }

    @Override
    @Transactional
    public void delete(Long userId, Long eventId) {
        Event event = getEvent(eventId);
        clubAccess.requireCoordinator(event.getClub().getId(), userId);
        // Always purge in dependency order. Even a draft can carry schedule items, a
        // certificate template, gallery media or announcements added during prep, and the
        // schema has no ON DELETE CASCADE — so a plain repository delete would defer the
        // DELETE to commit time and blow up there as an opaque 500. purgeEvent removes
        // children first (no-ops cleanly when there are none) and deletes the event row
        // itself immediately. See EntityPurgeService.
        entityPurgeService.purgeEvent(eventId);
    }

    @Override
    @Transactional(readOnly = true)
    public EventResponse getById(Long eventId, Long viewerId) {
        boolean saved = viewerId != null && savedEventRepository.existsByEventIdAndUserId(eventId, viewerId);
        return EventMapper.toResponse(getEvent(eventId), activeRegistrations(eventId), saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CalendarExport exportCalendar(Long eventId) {
        // readOnly tx keeps the session open so ICalendar can read the lazily-loaded club name.
        Event event = getEvent(eventId);
        return new CalendarExport(ICalendar.fileName(event), ICalendar.forEvent(event));
    }

    @Override
    @Transactional
    public EventResponse publish(Long userId, Long eventId) {
        Event event = getEvent(eventId);
        clubAccess.requireCoordinator(event.getClub().getId(), userId);
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new BadRequestException("A cancelled event cannot be published.");
        }
        EventStatus previous = event.getStatus();
        event.setStatus(EventStatus.PUBLISHED);
        event = eventRepository.save(event);
        if (previous != EventStatus.PUBLISHED) {
            notifyFollowersOfPublishedEvent(event);
        }
        return EventMapper.toResponse(event, activeRegistrations(eventId));
    }

    @Override
    @Transactional
    public EventResponse updateStatus(Long userId, Long eventId, EventStatus status) {
        if (status == null) {
            throw new BadRequestException("Status is required.");
        }
        Event event = getEvent(eventId);
        clubAccess.requireCoordinator(event.getClub().getId(), userId);
        EventStatus previous = event.getStatus();
        event.setStatus(status);
        event = eventRepository.save(event);
        if (status == EventStatus.COMPLETED && previous != EventStatus.COMPLETED) {
            certificateService.autoIssueForCompletedEvent(eventId);
        }
        if (status == EventStatus.PUBLISHED && previous != EventStatus.PUBLISHED) {
            notifyFollowersOfPublishedEvent(event);
        }
        if (status == EventStatus.CANCELLED && previous != EventStatus.CANCELLED) {
            // Alert every registered attendee that the event is off (in-app + email + WhatsApp).
            notifyRegistrantsOfChange(event, NotificationType.EVENT_CANCELLED,
                    "Event cancelled: " + event.getTitle(),
                    "\"" + event.getTitle() + "\" has been cancelled. We're sorry for the inconvenience.");
        }
        return EventMapper.toResponse(event, activeRegistrations(eventId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EventSummaryResponse> search(EventSearchCriteria c, boolean includeUnpublished,
                                                     Long viewerId, Pageable pageable) {
        Page<Event> page = eventRepository.findAll(
                EventSpecifications.build(c.q(), c.category(), c.mode(), c.status(), c.clubId(),
                        c.paid(), c.team(), c.featured(), c.from(), c.to(), includeUnpublished),
                pageable);
        Set<Long> savedIds = viewerId != null ? savedEventRepository.findEventIdsByUserId(viewerId) : Set.of();
        return PageResponse.from(page,
                e -> EventMapper.toSummary(e, activeRegistrations(e.getId()), savedIds.contains(e.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventSummaryResponse> featured(Long viewerId) {
        Set<Long> savedIds = viewerId != null ? savedEventRepository.findEventIdsByUserId(viewerId) : Set.of();
        return eventRepository.findByFeaturedTrueAndStatus(EventStatus.PUBLISHED).stream()
                .map(e -> EventMapper.toSummary(e, activeRegistrations(e.getId()), savedIds.contains(e.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventSummaryResponse> byClub(Long clubId) {
        return eventRepository.findByClubId(clubId).stream()
                .filter(e -> e.getStatus() != EventStatus.DRAFT)
                .map(e -> EventMapper.toSummary(e, activeRegistrations(e.getId())))
                .toList();
    }

    @Override
    @Transactional
    public void save(Long userId, Long eventId) {
        Event event = getEvent(eventId);
        // Idempotent: saving an already-saved event is a no-op rather than a duplicate row.
        if (savedEventRepository.existsByEventIdAndUserId(eventId, userId)) {
            return;
        }
        savedEventRepository.save(SavedEvent.builder()
                .event(event)
                .user(getUser(userId))
                .build());
    }

    @Override
    @Transactional
    public void unsave(Long userId, Long eventId) {
        // Idempotent: removing an event that isn't saved is a no-op.
        savedEventRepository.findByEventIdAndUserId(eventId, userId)
                .ifPresent(savedEventRepository::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventSummaryResponse> mySavedEvents(Long userId) {
        // Every event in this list is saved by the viewer, so saved=true throughout.
        return savedEventRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(SavedEvent::getEvent)
                .map(e -> EventMapper.toSummary(e, activeRegistrations(e.getId()), true))
                .toList();
    }

    @Override
    @Transactional
    public EventScheduleResponse addSchedule(Long userId, Long eventId, EventScheduleRequest request) {
        Event event = getEvent(eventId);
        clubAccess.requireActiveMember(event.getClub().getId(), userId);
        EventSchedule schedule = EventSchedule.builder()
                .event(event)
                .title(request.title().trim())
                .description(request.description())
                .startDateTime(request.startDateTime())
                .endDateTime(request.endDateTime())
                .dayNumber(request.dayNumber() != null ? request.dayNumber() : 1)
                .speaker(request.speaker())
                .venue(request.venue())
                .build();
        return EventMapper.toScheduleResponse(eventScheduleRepository.save(schedule));
    }

    @Override
    @Transactional
    public EventScheduleResponse updateSchedule(Long userId, Long eventId, Long scheduleId, EventScheduleRequest request) {
        Event event = getEvent(eventId);
        clubAccess.requireActiveMember(event.getClub().getId(), userId);
        EventSchedule schedule = eventScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", "id", scheduleId));
        if (schedule.getEvent() == null || !schedule.getEvent().getId().equals(eventId)) {
            throw new BadRequestException("This schedule item does not belong to the event.");
        }
        schedule.setTitle(request.title().trim());
        schedule.setDescription(request.description());
        schedule.setStartDateTime(request.startDateTime());
        schedule.setEndDateTime(request.endDateTime());
        schedule.setDayNumber(request.dayNumber() != null ? request.dayNumber() : 1);
        schedule.setSpeaker(request.speaker());
        schedule.setVenue(request.venue());
        return EventMapper.toScheduleResponse(eventScheduleRepository.save(schedule));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventScheduleResponse> listSchedules(Long eventId) {
        getEvent(eventId);
        return eventScheduleRepository.findByEventIdOrderByStartDateTimeAsc(eventId).stream()
                .map(EventMapper::toScheduleResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteSchedule(Long userId, Long eventId, Long scheduleId) {
        Event event = getEvent(eventId);
        clubAccess.requireActiveMember(event.getClub().getId(), userId);
        EventSchedule schedule = eventScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", "id", scheduleId));
        if (schedule.getEvent() == null || !schedule.getEvent().getId().equals(eventId)) {
            throw new BadRequestException("This schedule item does not belong to the event.");
        }
        eventScheduleRepository.delete(schedule);
    }

    // ---- helpers ----

    private void validateDates(EventRequest request) {
        if (!request.endDateTime().isAfter(request.startDateTime())) {
            throw new BadRequestException("End time must be after the start time.");
        }
        if (request.registrationDeadline() != null
                && request.registrationDeadline().isAfter(request.startDateTime())) {
            throw new BadRequestException("Registration deadline cannot be after the event start time.");
        }
    }

    private void applyPricingAndTeam(Event event, EventRequest request) {
        if (request.paidEvent()) {
            if (request.fee() == null || request.fee().signum() <= 0) {
                throw new BadRequestException("Paid events must have a fee greater than zero.");
            }
            event.setPaidEvent(true);
            event.setFee(request.fee());
        } else {
            event.setPaidEvent(false);
            event.setFee(BigDecimal.ZERO);
        }

        if (request.teamEvent()) {
            int min = request.minTeamSize() != null ? request.minTeamSize() : 2;
            Integer max = request.maxTeamSize();
            if (max != null && min > max) {
                throw new BadRequestException("Minimum team size cannot exceed the maximum team size.");
            }
            event.setTeamEvent(true);
            event.setMinTeamSize(min);
            event.setMaxTeamSize(max);
        } else {
            event.setTeamEvent(false);
            event.setMinTeamSize(null);
            event.setMaxTeamSize(null);
        }
    }

    private long activeRegistrations(Long eventId) {
        return registrationRepository.countByEventId(eventId)
                - registrationRepository.countByEventIdAndStatus(eventId, RegistrationStatus.CANCELLED);
    }

    /**
     * Best-effort fan-out: let everyone following the host club know a new event just went live.
     * {@code notifyUser} never throws and writes only notification rows (which carry no unique
     * constraints), so it is safe to call inside this REQUIRED transaction without REQUIRES_NEW.
     */
    private void notifyFollowersOfPublishedEvent(Event event) {
        Club club = event.getClub();
        if (club == null) {
            return;
        }
        List<ClubFollow> followers = clubFollowRepository.findByClubId(club.getId());
        if (followers.isEmpty()) {
            return;
        }
        String title = "New event from " + club.getName();
        String message = club.getName() + " just published \"" + event.getTitle() + "\".";
        String link = "/events/" + event.getId();
        for (ClubFollow follow : followers) {
            User follower = follow.getUser();
            if (follower != null) {
                notificationService.notifyUser(follower.getId(), NotificationType.ANNOUNCEMENT,
                        title, message, link);
            }
        }
    }

    /**
     * Fan out an event change to everyone currently registered (in-app + preference-gated email via
     * {@code notifyUser}, plus a best-effort WhatsApp line). Cancelled registrations are skipped.
     * All sends are fail-soft — {@code notifyUser} and the WhatsApp resolver never throw — so a
     * messaging hiccup can't roll back the event update inside this REQUIRED transaction.
     */
    private void notifyRegistrantsOfChange(Event event, NotificationType type,
                                           String title, String message) {
        List<Registration> registrations = registrationRepository.findByEventId(event.getId());
        if (registrations.isEmpty()) {
            return;
        }
        String link = "/events/" + event.getId();
        for (Registration reg : registrations) {
            if (reg.getStatus() == RegistrationStatus.CANCELLED) {
                continue;
            }
            User attendee = reg.getUser();
            if (attendee == null) {
                continue;
            }
            notificationService.notifyUser(attendee.getId(), type, title, message, link);
            whatsAppResolver.resolve().sendText(attendee.getPhone(), title + " — " + message);
        }
    }

    /** A null or non-positive capacity means "unlimited" — treated as the largest possible seat count. */
    private static int effectiveCapacity(Integer capacity) {
        return (capacity == null || capacity <= 0) ? Integer.MAX_VALUE : capacity;
    }

    private Event getEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", eventId));
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }
}
