package com.campusconnect.service;

import com.campusconnect.dto.request.AnnouncementRequest;
import com.campusconnect.dto.request.AnnouncementUpdateRequest;
import com.campusconnect.dto.response.AnnouncementResponse;

import java.util.List;

public interface AnnouncementService {

    /** Post an announcement. Authorization and notification fan-out depend on the scope. */
    AnnouncementResponse create(Long actingUserId, AnnouncementRequest request);

    /** Edit an announcement's title/body/pinned flag (author or the relevant coordinator). */
    AnnouncementResponse update(Long actingUserId, Long announcementId, AnnouncementUpdateRequest request);

    void delete(Long actingUserId, Long announcementId);

    List<AnnouncementResponse> listByClub(Long clubId);

    List<AnnouncementResponse> listByEvent(Long eventId);

    List<AnnouncementResponse> listGeneral();

    List<AnnouncementResponse> feed();
}
