package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.dto.request.PaymentInitiateRequest;
import com.campusconnect.dto.request.PaymentVerifyRequest;
import com.campusconnect.dto.response.PaymentConfigResponse;
import com.campusconnect.dto.response.PaymentResponse;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Payments", description = "Event fee payments via a pluggable gateway abstraction")
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/config")
    @Operation(summary = "Non-secret checkout configuration for the browser (active provider + publishable key)")
    public ApiResponse<PaymentConfigResponse> config() {
        return ApiResponse.success(paymentService.config());
    }

    @PostMapping("/initiate")
    @Operation(summary = "Pay the registration fee for a paid event")
    public ApiResponse<PaymentResponse> initiate(@AuthenticationPrincipal UserPrincipal principal,
                                                 @Valid @RequestBody PaymentInitiateRequest request) {
        return ApiResponse.success("Payment processed", paymentService.initiate(principal.getId(), request));
    }

    @PostMapping("/verify")
    @Operation(summary = "Confirm a payment after a client-side checkout (e.g. Razorpay) returns")
    public ApiResponse<PaymentResponse> verify(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody PaymentVerifyRequest request) {
        return ApiResponse.success("Payment confirmed", paymentService.verify(principal.getId(), request));
    }

    @GetMapping("/me")
    @Operation(summary = "List my payments")
    public ApiResponse<List<PaymentResponse>> myPayments(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(paymentService.myPayments(principal.getId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of my payments")
    public ApiResponse<PaymentResponse> getMyPayment(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long id) {
        return ApiResponse.success(paymentService.getMyPayment(principal.getId(), id));
    }

    @GetMapping("/event/{eventId}")
    @Operation(summary = "List payments for an event (club coordinator or admin)")
    public ApiResponse<List<PaymentResponse>> eventPayments(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable Long eventId) {
        return ApiResponse.success(paymentService.eventPayments(principal.getId(), eventId));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Refund a successful payment (club coordinator or admin)")
    public ApiResponse<PaymentResponse> refund(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable Long id) {
        return ApiResponse.success("Payment refunded", paymentService.refund(principal.getId(), id));
    }

    @GetMapping("/{id}/receipt")
    @Operation(summary = "Download a PDF receipt for a completed payment (payer, coordinator or admin)")
    public ResponseEntity<byte[]> receipt(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable Long id) {
        byte[] pdf = paymentService.renderReceipt(principal.getId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/receipts.zip")
    @Operation(summary = "Download PDF receipts for several completed payments as a ZIP "
            + "(each authorised for the payer, coordinator or admin)")
    public ResponseEntity<byte[]> receiptsZip(@AuthenticationPrincipal UserPrincipal principal,
                                              @RequestParam("ids") List<Long> ids) {
        byte[] zip = paymentService.renderReceiptsZip(principal.getId(), ids);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payment-receipts.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }
}
