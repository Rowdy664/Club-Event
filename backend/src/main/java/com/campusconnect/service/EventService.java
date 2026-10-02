package com.campusconnect.service;

import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.EventRequest;
import com.campusconnect.dto.request.EventScheduleRequest;
import com.campusconnect.dto.request.EventSearchCriteria;
import com.campusconnect.dto.response.EventResponse;
import com.campusconnect.dto.response.EventScheduleResponse;
import com.campusconnect.dto.response.EventSummaryResponse;
import com.campusconnect.entity.enums.EventStatus;
import com.campusconnect.service.calendar.CalendarExport;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface EventService {

    EventResponse create(Long userId, EventRequest request);

    EventResponse update(Long userId, Long eventId, EventRequest request);

    void delete(Long userId, Long eventId);

    EventResponse getById(Long eventId, Long viewerId);

    /** Render an event as a downloadable iCalendar (.ics) file for the public event page. */
    CalendarExport exportCalendar(Long eventId);

    EventResponse publish(Long userId, Long eventId);

    EventResponse updateStatus(Long userId, Long eventId, EventStatus status);

    PageResponse<EventSummaryResponse> search(EventSearchCriteria criteria, boolean includeUnpublished,
                                              Long viewerId, Pageable pageable);

    List<EventSummaryResponse> featured(Long viewerId);

    List<EventSummaryResponse> byClub(Long clubId);

    void save(Long userId, Long eventId);

    void unsave(Long userId, Long eventId);

    List<EventSummaryResponse> mySavedEvents(Long userId);

    EventScheduleResponse addSchedule(Long userId, Long eventId, EventScheduleRequest request);

    EventScheduleResponse updateSchedule(Long userId, Long eventId, Long scheduleId, EventScheduleRequest request);

    List<EventScheduleResponse> listSchedules(Long eventId);

    void deleteSchedule(Long userId, Long eventId, Long scheduleId);
}
