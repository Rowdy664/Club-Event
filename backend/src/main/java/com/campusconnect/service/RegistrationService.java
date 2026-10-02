package com.campusconnect.service;

import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.RegistrationRequest;
import com.campusconnect.dto.response.RegistrationResponse;
import org.springframework.data.domain.Pageable;

public interface RegistrationService {

    RegistrationResponse register(Long userId, RegistrationRequest request);

    void cancel(Long userId, Long registrationId);

    RegistrationResponse getMyRegistrationForEvent(Long userId, Long eventId);

    PageResponse<RegistrationResponse> myRegistrations(Long userId, Pageable pageable);

    PageResponse<RegistrationResponse> eventRegistrations(Long actingUserId, Long eventId, Pageable pageable);

    /** Returns the QR-code PNG for a ticket. Accessible to the ticket owner or a club coordinator. */
    byte[] ticketQr(Long userId, Long registrationId);

    /**
     * Send a one-time verification code for a ticket to its owner (email + WhatsApp), so they can
     * prove the ticket is genuinely theirs. Owner-only; the ticket must be issued (active seat).
     */
    void requestTicketVerification(Long userId, Long registrationId);

    /**
     * Confirm a ticket-verification challenge with the 6-digit {@code code}. Owner-only. On success
     * the ticket is flagged verified and the code is burned. Returns the updated registration.
     */
    RegistrationResponse confirmTicketVerification(Long userId, Long registrationId, String code);

    /**
     * Move waitlisted attendees into any free seats for the given event, oldest first, notifying each
     * promoted attendee. Called when a seat is released or an event's capacity is raised. No-op when the
     * event has no waitlist or no free seats.
     */
    void promoteWaitlist(Long eventId);
}
