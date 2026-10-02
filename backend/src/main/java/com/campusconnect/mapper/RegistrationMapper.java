package com.campusconnect.mapper;

import com.campusconnect.dto.response.RegistrationResponse;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.Registration;
import com.campusconnect.entity.Team;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.RegistrationStatus;

public final class RegistrationMapper {

    private RegistrationMapper() {
    }

    public static RegistrationResponse toResponse(Registration registration) {
        if (registration == null) {
            return null;
        }
        Event event = registration.getEvent();
        User user = registration.getUser();
        Team team = registration.getTeam();

        RegistrationStatus status = registration.getStatus();
        boolean paidEvent = event != null && event.isPaidEvent()
                && event.getFee() != null && event.getFee().signum() > 0;
        boolean active = status == RegistrationStatus.REGISTERED || status == RegistrationStatus.CONFIRMED;
        // A ticket is issuable once the attendee holds an active seat, and — for a paid event —
        // only after payment has cleared (which flips the registration to CONFIRMED).
        boolean ticketReady = active && (!paidEvent || status == RegistrationStatus.CONFIRMED);

        return new RegistrationResponse(
                registration.getId(),
                event != null ? event.getId() : null,
                event != null ? event.getTitle() : null,
                user != null ? user.getId() : null,
                user != null ? user.getFullName() : null,
                team != null ? team.getId() : null,
                team != null ? team.getName() : null,
                registration.getType(),
                status,
                registration.getTicketCode(),
                paidEvent,
                ticketReady,
                registration.isTicketVerified(),
                event != null ? event.getStatus() : null,
                registration.getCreatedAt()
        );
    }
}
