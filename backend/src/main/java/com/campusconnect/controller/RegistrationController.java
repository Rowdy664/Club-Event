package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.common.PageRequests;
import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.RegistrationRequest;
import com.campusconnect.dto.request.VerifyTicketRequest;
import com.campusconnect.dto.response.RegistrationResponse;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.RegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/registrations")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Registrations", description = "Event registration, tickets and QR codes")
public class RegistrationController {

    private final RegistrationService registrationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register the current user for an event")
    public ApiResponse<RegistrationResponse> register(@AuthenticationPrincipal UserPrincipal principal,
                                                       @Valid @RequestBody RegistrationRequest request) {
        return ApiResponse.success("Registration successful",
                registrationService.register(principal.getId(), request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel one of my registrations")
    public ApiResponse<Void> cancel(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable Long id) {
        registrationService.cancel(principal.getId(), id);
        return ApiResponse.message("Registration cancelled");
    }

    @GetMapping("/me")
    @Operation(summary = "List my registrations")
    public ApiResponse<PageResponse<RegistrationResponse>> myRegistrations(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(registrationService.myRegistrations(
                principal.getId(),
                PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/me/event/{eventId}")
    @Operation(summary = "Get my registration for a specific event")
    public ApiResponse<RegistrationResponse> myRegistrationForEvent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long eventId) {
        return ApiResponse.success(registrationService.getMyRegistrationForEvent(principal.getId(), eventId));
    }

    @GetMapping("/event/{eventId}")
    @Operation(summary = "List registrations for an event (club coordinator only)")
    public ApiResponse<PageResponse<RegistrationResponse>> eventRegistrations(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(registrationService.eventRegistrations(
                principal.getId(), eventId,
                PageRequests.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"))));
    }

    @GetMapping("/{id}/qr")
    @Operation(summary = "Download the QR code for a ticket")
    public ResponseEntity<byte[]> ticketQr(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable Long id) {
        byte[] png = registrationService.ticketQr(principal.getId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"ticket-" + id + ".png\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    @PostMapping("/{id}/verify/request")
    @Operation(summary = "Send a one-time code to verify my ticket (email + WhatsApp)")
    public ApiResponse<Void> requestTicketVerification(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable Long id) {
        registrationService.requestTicketVerification(principal.getId(), id);
        return ApiResponse.message("Verification code sent to your email and WhatsApp.");
    }

    @PostMapping("/{id}/verify/confirm")
    @Operation(summary = "Confirm my ticket with the 6-digit code")
    public ApiResponse<RegistrationResponse> confirmTicketVerification(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody VerifyTicketRequest request) {
        return ApiResponse.success("Ticket verified.",
                registrationService.confirmTicketVerification(principal.getId(), id, request.code()));
    }
}
