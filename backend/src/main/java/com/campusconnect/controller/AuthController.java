package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.dto.request.ForgotPasswordRequest;
import com.campusconnect.dto.request.LoginOtpRequest;
import com.campusconnect.dto.request.LoginRequest;
import com.campusconnect.dto.request.RefreshTokenRequest;
import com.campusconnect.dto.request.RegisterRequest;
import com.campusconnect.dto.request.ResetPasswordRequest;
import com.campusconnect.dto.request.VerifyOtpRequest;
import com.campusconnect.dto.response.AuthResponse;
import com.campusconnect.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Register, login, token refresh and password recovery")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a new student account")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful. Please verify your email.", response));
    }

    @Operation(summary = "Authenticate; returns tokens, or a 2FA challenge when enabled")
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest servletRequest) {
        AuthResponse response = authService.login(request, clientIp(servletRequest));
        return ApiResponse.success(
                response.twoFactorRequired()
                        ? "Verification code sent to your email."
                        : "Login successful.",
                response);
    }

    @Operation(summary = "Complete a two-factor login by verifying the emailed code")
    @PostMapping("/verify-otp")
    public ApiResponse<AuthResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request,
                                               HttpServletRequest servletRequest) {
        return ApiResponse.success("Login successful.",
                authService.verifyOtp(request, clientIp(servletRequest)));
    }

    @Operation(summary = "Passwordless login: send a one-time code by email and WhatsApp")
    @PostMapping("/login/otp")
    public ApiResponse<AuthResponse> requestLoginOtp(@Valid @RequestBody LoginOtpRequest request,
                                                     HttpServletRequest servletRequest) {
        AuthResponse response = authService.requestLoginOtp(request, clientIp(servletRequest));
        // Same message whether or not an account matched — never leak account existence.
        // Complete the login via POST /verify-otp with the returned challenge token.
        return ApiResponse.success(
                "If an account matches, a sign-in code has been sent by email and WhatsApp.",
                response);
    }

    @Operation(summary = "Exchange a valid refresh token for a new access token")
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "Verify an email address using the emailed token")
    @GetMapping("/verify")
    public ApiResponse<Void> verify(@RequestParam("token") String token) {
        authService.verifyEmail(token);
        return ApiResponse.message("Email verified successfully. You can now sign in.");
    }

    @Operation(summary = "Request a password reset link")
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        // Always return the same response so account existence is not leaked.
        return ApiResponse.message("If an account exists for that email, a reset link has been sent.");
    }

    @Operation(summary = "Reset a password using the emailed token")
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.message("Password has been reset. Please sign in with your new password.");
    }

    @Operation(summary = "Logout (stateless — client discards its tokens)")
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.message("Logged out.");
    }

    /**
     * Best-effort client IP for rate-limit scoping. Honours a single-hop
     * {@code X-Forwarded-For} (first entry) when present — deployments behind a
     * trusted proxy set this; direct deployments fall back to the socket address.
     * This only scopes brute-force counters, so a spoofed value cannot escalate
     * privileges, at worst it shifts an attacker's own bucket.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
