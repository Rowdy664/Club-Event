package com.campusconnect.dto.response;

import com.campusconnect.entity.enums.EventStatus;
import com.campusconnect.entity.enums.RegistrationStatus;
import com.campusconnect.entity.enums.RegistrationType;

import java.time.Instant;

public record RegistrationResponse(
        Long id,
        Long eventId,
        String eventTitle,
        Long userId,
        String userName,
        Long teamId,
        String teamName,
        RegistrationType type,
        RegistrationStatus status,
        String ticketCode,
        boolean paidEvent,
        boolean ticketReady,
        boolean ticketVerified,
        EventStatus eventStatus,
        Instant createdAt
) {
}
